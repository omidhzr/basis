package com.example.basis.request;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A request for a discount on the standard rate, as it currently stands.
 *
 * <p>The rules below are pure: they depend only on this request and the
 * caller, never on concurrent state, so evaluating them before a write costs
 * nothing and produces a better message than a guard failure could. The
 * version and status guards are deliberately absent -- those live in the
 * UPDATE statement.
 */
public record ExceptionRequest(
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

    /**
     * Four-eyes is checked before the role, so that someone declining their
     * own request is told the useful thing rather than that they lack a role.
     */
    public Optional<Refusal> refuseDecisionBy(String actor, UserRole role) {
        if (requestedBy.equals(actor)) {
            return Optional.of(new Refusal(RefusalReason.SELF_DECISION,
                    "A request cannot be decided by the person who raised it."));
        }
        if (role != UserRole.REVIEWER) {
            return Optional.of(new Refusal(RefusalReason.NOT_REVIEWER,
                    "Deciding a request requires the " + UserRole.REVIEWER + " role."));
        }
        return Optional.empty();
    }

    public Optional<Refusal> refuseAmendBy(String actor) {
        return refuseUnlessRequester(actor, "amended");
    }

    public Optional<Refusal> refuseWithdrawalBy(String actor) {
        return refuseUnlessRequester(actor, "withdrawn");
    }

    private Optional<Refusal> refuseUnlessRequester(String actor, String action) {
        if (requestedBy.equals(actor)) {
            return Optional.empty();
        }
        return Optional.of(new Refusal(RefusalReason.NOT_REQUESTER,
                "A request may only be " + action + " by the person who raised it."));
    }
}
