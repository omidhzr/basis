package com.example.basis.request;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The wire shapes. Bounds come from the validation table in CLAUDE.md and are
 * expressed through {@link RequestLimits} so that the annotation, the database
 * constraint and any message quoting a bound cannot drift apart.
 */
public final class RequestBodies {

    private RequestBodies() {
    }

    public record Create(
            @NotBlank
            @Size(max = RequestLimits.MAX_APPLICATION_ID_LENGTH)
            String applicationId,

            @NotNull
            @Min(RequestLimits.MIN_DISCOUNT_BPS)
            @Max(RequestLimits.MAX_DISCOUNT_BPS)
            Integer discountBps,

            @NotBlank
            @Size(min = RequestLimits.MIN_REASON_LENGTH, max = RequestLimits.MAX_REASON_LENGTH)
            String reason) {
    }

    /**
     * Both fields are optional -- an amendment may change either or both --
     * but a value that is present must still be in range.
     */
    public record Amend(
            @Min(RequestLimits.MIN_DISCOUNT_BPS)
            @Max(RequestLimits.MAX_DISCOUNT_BPS)
            Integer discountBps,

            @Size(min = RequestLimits.MIN_REASON_LENGTH, max = RequestLimits.MAX_REASON_LENGTH)
            String reason,

            @NotNull
            @Positive
            Integer version) {
    }

    public record Decide(
            @NotNull
            Decision decision,

            @NotNull
            @Positive
            Integer version,

            @Size(max = RequestLimits.MAX_REASON_LENGTH)
            String note) {
    }

    public record Withdraw(
            @NotNull
            @Positive
            Integer version) {
    }

    /**
     * What a write returns, and what each row of the queue carries. History is
     * not included here: a write reports what the request now is, and the
     * caller who wants the trail reads the request itself.
     */
    public record View(
            UUID id,
            String applicationId,
            int discountBps,
            String reason,
            RequestStatus status,
            int version,
            String requestedBy,
            String decidedBy,
            Instant decidedAt,
            Instant createdAt,
            Instant updatedAt) {

        public static View of(ExceptionRequest request) {
            return new View(
                    request.id(),
                    request.applicationId(),
                    request.discountBps(),
                    request.reason(),
                    request.status(),
                    request.version(),
                    request.requestedBy(),
                    request.decidedBy(),
                    request.decidedAt(),
                    request.createdAt(),
                    request.updatedAt());
        }
    }

    /**
     * A request together with the trail that produced it, newest first. The
     * two travel in one response so that a client cannot render a request
     * beside a history that has moved on since.
     */
    public record Detail(
            View request,
            List<Entry> history) {

        public static Detail of(ExceptionRequest request, List<HistoryEntry> history, ObjectMapper json) {
            return new Detail(View.of(request), history.stream().map(entry -> Entry.of(entry, json)).toList());
        }
    }

    /**
     * One history entry on the wire. The payload is the object it was stored
     * as rather than the string it is stored in: a client that has to parse a
     * string out of a parsed document is doing the service's job.
     */
    public record Entry(
            EntryType entryType,
            int version,
            String actor,
            JsonNode payload,
            Instant occurredAt) {

        static Entry of(HistoryEntry entry, ObjectMapper json) {
            return new Entry(
                    entry.entryType(),
                    entry.version(),
                    entry.actor(),
                    readPayload(entry, json),
                    entry.occurredAt());
        }

        private static JsonNode readPayload(HistoryEntry entry, ObjectMapper json) {
            if (entry.payload() == null) {
                return null;
            }
            try {
                return json.readTree(entry.payload());
            } catch (JsonProcessingException e) {
                // The service wrote this column; unreadable means the trail is
                // corrupt, which is worth failing over rather than hiding.
                throw new IllegalStateException("History entry " + entry.id() + " holds an unreadable payload", e);
            }
        }
    }

    /**
     * What the mortgage process consumes: the discount that was authorised,
     * and enough of the decision to answer who allowed it and when.
     */
    public record ApprovedException(
            String applicationId,
            UUID requestId,
            int discountBps,
            String reason,
            String requestedBy,
            String decidedBy,
            Instant decidedAt) {

        public static ApprovedException of(ExceptionRequest request) {
            return new ApprovedException(
                    request.applicationId(),
                    request.id(),
                    request.discountBps(),
                    request.reason(),
                    request.requestedBy(),
                    request.decidedBy(),
                    request.decidedAt());
        }
    }
}
