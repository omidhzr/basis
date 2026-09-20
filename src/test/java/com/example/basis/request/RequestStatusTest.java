package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The state machine, asserted against the diagram in CLAUDE.md rather than
 * against the expression that implements it:
 *
 * <pre>
 * PENDING --amend--&gt; PENDING (version + 1)
 * PENDING --approve--&gt; APPROVED
 * PENDING --decline--&gt; DECLINED
 * PENDING --withdraw--&gt; WITHDRAWN
 * APPROVED | DECLINED | WITHDRAWN --&gt; (terminal)
 * </pre>
 *
 * <p>Plain JUnit: no Spring context and no database, which is what keeps this
 * suite able to state the rules independently of how they are enforced.
 */
class RequestStatusTest {

    /** The four transitions leaving PENDING in the diagram above. */
    private static final Set<EntryType> TRANSITIONS_FROM_PENDING =
            EnumSet.of(EntryType.AMENDED, EntryType.APPROVED, EntryType.DECLINED, EntryType.WITHDRAWN);

    @Test
    @DisplayName("every transition leaving PENDING is accepted")
    void pendingAcceptsItsTransitions() {
        assertThat(TRANSITIONS_FROM_PENDING)
                .allSatisfy(transition -> assertThat(RequestStatus.PENDING.allows(transition))
                        .as("PENDING should allow %s", transition)
                        .isTrue());
    }

    @Test
    @DisplayName("no transition leaves a terminal state")
    void terminalStatesAcceptNothing() {
        for (RequestStatus terminal : EnumSet.of(
                RequestStatus.APPROVED, RequestStatus.DECLINED, RequestStatus.WITHDRAWN)) {
            for (EntryType transition : EntryType.values()) {
                assertThat(terminal.allows(transition))
                        .as("%s should refuse %s", terminal, transition)
                        .isFalse();
            }
        }
    }

    @Test
    @DisplayName("creation is not a transition out of any state")
    void creationIsNotATransition() {
        for (RequestStatus status : RequestStatus.values()) {
            assertThat(status.allows(EntryType.CREATED))
                    .as("%s should refuse CREATED, which only ever starts a request", status)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("PENDING is the only non-terminal state")
    void onlyPendingIsOpen() {
        assertThat(RequestStatus.PENDING.isTerminal()).isFalse();
        assertThat(RequestStatus.APPROVED.isTerminal()).isTrue();
        assertThat(RequestStatus.DECLINED.isTerminal()).isTrue();
        assertThat(RequestStatus.WITHDRAWN.isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a decision produces the status and the history entry that name it")
    void decisionsMapToTheirOutcomes() {
        assertThat(Decision.APPROVE.status()).isEqualTo(RequestStatus.APPROVED);
        assertThat(Decision.APPROVE.entryType()).isEqualTo(EntryType.APPROVED);
        assertThat(Decision.DECLINE.status()).isEqualTo(RequestStatus.DECLINED);
        assertThat(Decision.DECLINE.entryType()).isEqualTo(EntryType.DECLINED);
    }
}
