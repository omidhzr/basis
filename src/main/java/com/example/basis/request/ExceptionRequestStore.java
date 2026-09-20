package com.example.basis.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every write here is one transaction: the guarded UPDATE and the history
 * INSERT commit together or not at all.
 *
 * <p>The version and status checks live inside each statement rather than in a
 * read-then-compare, so two callers acting on the same version cannot both
 * win. A statement that matches no row is classified afterwards by re-reading
 * the request, which decides only what the caller is told, never whether the
 * write proceeds.
 */
@Repository
public class ExceptionRequestStore {

    private static final String SELECT_COLUMNS = """
            SELECT id, application_id, discount_bps, reason, status, version,
                   requested_by, decided_by, decided_at, created_at, updated_at
              FROM exception_request
            """;

    private final JdbcClient db;
    private final ObjectMapper json;

    public ExceptionRequestStore(JdbcClient db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    @Transactional
    public ExceptionRequest create(String applicationId, int discountBps, String reason, String actor) {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();

        db.sql("""
                INSERT INTO exception_request (id, application_id, discount_bps, reason, status,
                                               version, requested_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'PENDING', 1, ?, ?, ?)
                """)
                .params(id, applicationId, discountBps, reason, actor, at(now), at(now))
                .update();

        Map<String, Object> carried = new LinkedHashMap<>();
        carried.put("discountBps", discountBps);
        carried.put("reason", reason);
        appendHistory(id, EntryType.CREATED, 1, actor, payload(carried), now);

        return require(id);
    }

    @Transactional
    public ExceptionRequest amend(UUID id, int version, String actor, Integer discountBps, String reason) {
        refuseUnless(require(id).refuseAmendBy(actor));

        Instant now = Instant.now();
        int rows = db.sql("""
                UPDATE exception_request
                   SET discount_bps = COALESCE(?, discount_bps),
                       reason       = COALESCE(?, reason),
                       version      = version + 1,
                       updated_at   = ?
                 WHERE id = ? AND version = ? AND status = 'PENDING'
                """)
                .params(discountBps, reason, at(now), id, version)
                .update();

        if (rows == 0) {
            throw classify(id, EntryType.AMENDED);
        }

        Map<String, Object> carried = new LinkedHashMap<>();
        if (discountBps != null) {
            carried.put("discountBps", discountBps);
        }
        if (reason != null) {
            carried.put("reason", reason);
        }
        appendHistory(id, EntryType.AMENDED, version + 1, actor, payload(carried), now);

        return require(id);
    }

    @Transactional
    public ExceptionRequest decide(UUID id, int version, String actor, UserRole role,
                                   Decision decision, String note) {
        refuseUnless(require(id).refuseDecisionBy(actor, role));

        Instant now = Instant.now();
        int rows = db.sql("""
                UPDATE exception_request
                   SET status = ?, decided_by = ?, decided_at = ?, updated_at = ?
                 WHERE id = ? AND version = ? AND status = 'PENDING'
                """)
                .params(decision.status().name(), actor, at(now), at(now), id, version)
                .update();

        if (rows == 0) {
            throw classify(id, decision.entryType());
        }

        Map<String, Object> carried = new LinkedHashMap<>();
        if (note != null && !note.isBlank()) {
            carried.put("note", note);
        }
        appendHistory(id, decision.entryType(), version, actor, payload(carried), now);

        return require(id);
    }

    @Transactional
    public ExceptionRequest withdraw(UUID id, int version, String actor) {
        refuseUnless(require(id).refuseWithdrawalBy(actor));

        Instant now = Instant.now();
        int rows = db.sql("""
                UPDATE exception_request
                   SET status = 'WITHDRAWN', updated_at = ?
                 WHERE id = ? AND version = ? AND status = 'PENDING'
                """)
                .params(at(now), id, version)
                .update();

        if (rows == 0) {
            throw classify(id, EntryType.WITHDRAWN);
        }

        appendHistory(id, EntryType.WITHDRAWN, version, actor, payload(new LinkedHashMap<>()), now);

        return require(id);
    }

    public Optional<ExceptionRequest> find(UUID id) {
        return db.sql(SELECT_COLUMNS + " WHERE id = ?")
                .param(id)
                .query(ExceptionRequestStore::toRequest)
                .optional();
    }

    public List<HistoryEntry> historyOf(UUID id) {
        return db.sql("""
                SELECT id, request_id, entry_type, version, actor, payload, occurred_at
                  FROM request_history
                 WHERE request_id = ?
                 ORDER BY occurred_at DESC, version DESC
                """)
                .param(id)
                .query(ExceptionRequestStore::toEntry)
                .list();
    }

    private ExceptionRequest require(UUID id) {
        return find(id).orElseThrow(() -> new RequestNotFoundException(id));
    }

    private static void refuseUnless(Optional<Refusal> refusal) {
        refusal.ifPresent(r -> {
            throw new RefusedException(r);
        });
    }

    /**
     * Decides what to say about a guarded write that matched no row. The write
     * has already failed by this point, so re-reading here cannot reintroduce
     * the race the guard exists to prevent.
     */
    private RuntimeException classify(UUID id, EntryType attempted) {
        ExceptionRequest current = find(id).orElse(null);
        if (current == null) {
            return new RequestNotFoundException(id);
        }
        if (!current.status().allows(attempted)) {
            return new ConflictException(current,
                    "This request is " + current.status() + " and can no longer be changed.");
        }
        return new ConflictException(current,
                "This request has moved on and is now at version " + current.version()
                        + ". Reload it and review the current version rather than resubmitting.");
    }

    private void appendHistory(UUID requestId, EntryType type, int version, String actor,
                               String payload, Instant when) {
        db.sql("""
                INSERT INTO request_history (id, request_id, entry_type, version, actor, payload, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """)
                .params(UUID.randomUUID(), requestId, type.name(), version, actor, payload, at(when))
                .update();
    }

    private String payload(Map<String, Object> values) {
        try {
            return json.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            // These maps hold only strings and ints, so this cannot fail in
            // practice. Failing loudly beats storing a broken audit entry.
            throw new IllegalStateException("Could not serialise a history payload", e);
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static ExceptionRequest toRequest(ResultSet rs, int rowNum) throws SQLException {
        return new ExceptionRequest(
                rs.getObject("id", UUID.class),
                rs.getString("application_id"),
                rs.getInt("discount_bps"),
                rs.getString("reason"),
                RequestStatus.valueOf(rs.getString("status")),
                rs.getInt("version"),
                rs.getString("requested_by"),
                rs.getString("decided_by"),
                instantOf(rs.getObject("decided_at", OffsetDateTime.class)),
                instantOf(rs.getObject("created_at", OffsetDateTime.class)),
                instantOf(rs.getObject("updated_at", OffsetDateTime.class)));
    }

    private static HistoryEntry toEntry(ResultSet rs, int rowNum) throws SQLException {
        return new HistoryEntry(
                rs.getObject("id", UUID.class),
                rs.getObject("request_id", UUID.class),
                EntryType.valueOf(rs.getString("entry_type")),
                rs.getInt("version"),
                rs.getString("actor"),
                rs.getString("payload"),
                instantOf(rs.getObject("occurred_at", OffsetDateTime.class)));
    }

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
