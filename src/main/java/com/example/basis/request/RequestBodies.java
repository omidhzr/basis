package com.example.basis.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
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
     * What a write returns. History is not included here: reading a request
     * and its history is the queries slice, and inventing half of it now would
     * put a second shape in the way of the one specified there.
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
}
