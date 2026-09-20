package com.example.basis.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
    private final TransactionTemplate transactions;

    public ExceptionRequestStore(JdbcClient db, ObjectMapper json, TransactionTemplate transactions) {
        this.db = db;
        this.json = json;
        this.transactions = transactions;
    }

    /**
     * Creates a request, or replays the create this key already stands for.
     *
     * <p>The transaction is opened explicitly rather than with an annotation
     * because the duplicate key has to be caught <em>outside</em> it: the
     * violation poisons the transaction, so the stored response cannot be read
     * until it has rolled back. A self-invoked annotated method would not be
     * proxied and would not be transactional at all.
     *
     * <p>Waiting for that rollback is safe. H2 holds the second insert until
     * the transaction owning the key ends -- if it committed, the record is
     * there to replay; if it rolled back, this insert succeeds and this caller
     * is the one that created the request.
     */
    public CreateOutcome create(String applicationId, int discountBps, String reason,
                                String actor, String idempotencyKey) {
        try {
            return transactions.execute(status ->
                    insertNew(applicationId, discountBps, reason, actor, idempotencyKey));
        } catch (DuplicateKeyException alreadyUsed) {
            return replay(idempotencyKey, actor, applicationId, discountBps, reason);
        }
    }

    private CreateOutcome insertNew(String applicationId, int discountBps, String reason,
                                    String actor, String idempotencyKey) {
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

        // Written last, from the value the endpoint returns, so that "the
        // stored response is the response the caller received" holds by
        // construction rather than by two places agreeing.
        String responseBody = serialise(RequestBodies.View.of(require(id)));
        db.sql("""
                INSERT INTO idempotency_record ("key", caller, request_id, response_body,
                                                status_code, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """)
                .params(idempotencyKey, actor, id, responseBody, HttpStatus.CREATED.value(), at(now))
                .update();

        return new CreateOutcome(id, HttpStatus.CREATED.value(), responseBody, false);
    }

    /**
     * Answers a create whose key is already taken. The comparison is against
     * the stored response, not against the request row: the row moves when the
     * request is amended, and a retry of the original create must still be
     * recognised as that create rather than told it differs.
     */
    private CreateOutcome replay(String key, String caller,
                                 String applicationId, int discountBps, String reason) {
        IdempotencyRecord stored = findRecord(key, caller).orElseThrow(() ->
                // Unreachable: the insert above only fails once the transaction
                // holding this key has committed. Failing loudly beats
                // inventing a response for a state that cannot occur.
                new IllegalStateException(
                        "Idempotency key '" + key + "' was taken but no record was stored"));

        if (!isSameCreate(stored, applicationId, discountBps, reason)) {
            throw new KeyReusedException(key);
        }
        return new CreateOutcome(stored.requestId(), stored.statusCode(), stored.responseBody(), true);
    }

    private boolean isSameCreate(IdempotencyRecord stored,
                                 String applicationId, int discountBps, String reason) {
        try {
            // Compared field by field rather than byte by byte: two bodies
            // differing only in whitespace or field order are the same create.
            JsonNode original = json.readTree(stored.responseBody());
            return original.path("applicationId").asText().equals(applicationId)
                    && original.path("discountBps").asInt() == discountBps
                    && original.path("reason").asText().equals(reason);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read a stored idempotent response", e);
        }
    }

    private Optional<IdempotencyRecord> findRecord(String key, String caller) {
        return db.sql("""
                SELECT "key", caller, request_id, response_body, status_code, created_at
                  FROM idempotency_record
                 WHERE "key" = ? AND caller = ?
                """)
                .params(key, caller)
                .query(ExceptionRequestStore::toRecord)
                .optional();
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
        return serialise(values);
    }

    private String serialise(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Records of strings, ints and instants, so this cannot fail in
            // practice. Failing loudly beats storing a broken audit entry or a
            // response that cannot be replayed.
            throw new IllegalStateException("Could not serialise " + value.getClass().getSimpleName(), e);
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

    private static IdempotencyRecord toRecord(ResultSet rs, int rowNum) throws SQLException {
        return new IdempotencyRecord(
                rs.getString("key"),
                rs.getString("caller"),
                rs.getObject("request_id", UUID.class),
                rs.getString("response_body"),
                rs.getInt("status_code"),
                instantOf(rs.getObject("created_at", OffsetDateTime.class)));
    }

    private static Instant instantOf(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
