package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Four-eyes, the reviewer role check, and ownership of an amendment or a
 * withdrawal. Each is asserted for the caller it permits and the caller it
 * refuses, and each refusal is asserted to explain itself -- a caller who is
 * told only "403" cannot tell a role problem from a four-eyes one.
 *
 * <p>Plain JUnit: these rules depend on the caller and the stored request,
 * never on concurrent state, so they need neither a database nor a context.
 */
class ExceptionRequestRulesTest {

    private static final String REQUESTER = "rm-1";
    private static final String SOMEONE_ELSE = "reviewer-1";

    private final ExceptionRequest request = pendingRequestRaisedBy(REQUESTER);

    @Test
    @DisplayName("a reviewer who did not raise the request may decide it")
    void anotherReviewerMayDecide() {
        assertThat(request.refuseDecisionBy(SOMEONE_ELSE, UserRole.REVIEWER)).isEmpty();
    }

    @Test
    @DisplayName("the requester may not decide their own request, whatever role they hold")
    void fourEyesRefusesTheRequester() {
        assertThat(request.refuseDecisionBy(REQUESTER, UserRole.REVIEWER))
                .hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RefusalReason.SELF_DECISION);
                    assertThat(refusal.detail()).contains("cannot be decided by the person who raised it");
                });

        // Four-eyes is about who raised it, not about the role claimed, so a
        // requester carrying the reviewer role is refused for the same reason.
        assertThat(request.refuseDecisionBy(REQUESTER, UserRole.RELATIONSHIP_MANAGER))
                .hasValueSatisfying(refusal ->
                        assertThat(refusal.reason()).isEqualTo(RefusalReason.SELF_DECISION));
    }

    @Test
    @DisplayName("a caller without the reviewer role may not decide")
    void roleCheckRefusesARelationshipManager() {
        assertThat(request.refuseDecisionBy(SOMEONE_ELSE, UserRole.RELATIONSHIP_MANAGER))
                .hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RefusalReason.NOT_REVIEWER);
                    assertThat(refusal.detail()).contains(UserRole.REVIEWER.name());
                });
    }

    @Test
    @DisplayName("only the requester may amend")
    void amendIsOwnedByTheRequester() {
        assertThat(request.refuseAmendBy(REQUESTER)).isEmpty();
        assertThat(request.refuseAmendBy(SOMEONE_ELSE))
                .hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RefusalReason.NOT_REQUESTER);
                    assertThat(refusal.detail()).contains("amended");
                });
    }

    @Test
    @DisplayName("only the requester may withdraw")
    void withdrawalIsOwnedByTheRequester() {
        assertThat(request.refuseWithdrawalBy(REQUESTER)).isEmpty();
        assertThat(request.refuseWithdrawalBy(SOMEONE_ELSE))
                .hasValueSatisfying(refusal -> {
                    assertThat(refusal.reason()).isEqualTo(RefusalReason.NOT_REQUESTER);
                    assertThat(refusal.detail()).contains("withdrawn");
                });
    }

    @Test
    @DisplayName("every refusal carries a detail a caller could act on")
    void refusalsExplainThemselves() {
        for (Optional<Refusal> refusal : java.util.List.of(
                request.refuseDecisionBy(REQUESTER, UserRole.REVIEWER),
                request.refuseDecisionBy(SOMEONE_ELSE, UserRole.RELATIONSHIP_MANAGER),
                request.refuseAmendBy(SOMEONE_ELSE),
                request.refuseWithdrawalBy(SOMEONE_ELSE))) {

            assertThat(refusal).hasValueSatisfying(r ->
                    assertThat(r.detail()).isNotBlank().endsWith("."));
        }
    }

    private static ExceptionRequest pendingRequestRaisedBy(String requester) {
        Instant now = Instant.now();
        return new ExceptionRequest(
                UUID.randomUUID(), "APP-1", 25, "Retention case for a long-standing client",
                RequestStatus.PENDING, 1, requester, null, null, now, now);
    }
}
