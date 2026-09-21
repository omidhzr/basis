package com.example.basis;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.basis.request.RequestLimits;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * The demonstration seed is SQL written by hand, so nothing but this test stops
 * it contradicting the invariants the service enforces: an {@code APPROVED} row
 * with no {@code decided_at}, or a version-2 request with one history entry.
 * The assertions come from the invariants rather than from the seed's rows, so
 * a column that stops being populated fails here.
 *
 * <p>The database URL is overridden to one of this class's own. The default URL
 * is kept alive for the whole JVM and shared by every context in the suite, so
 * a dev-profile context on it would leak the seed into the tests that assume it
 * is absent, and would insert the seed's fixed identifiers a second time if the
 * context were ever created twice.
 */
@SpringBootTest
@ActiveProfiles("dev")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:seed-fixture;DB_CLOSE_DELAY=-1")
class SeedFixtureTest {

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("the dev profile loads the seed")
    void seedIsLoaded() {
        assertThat(countWhere("TRUE")).isPositive();
    }

    @Test
    @DisplayName("a request amended to version 2 carries both its creation and its amendment")
    void amendedRequestHasBothEntries() {
        List<UUID> amended = db.sql("SELECT id FROM exception_request WHERE status = 'PENDING' AND version = 2")
                .query(UUID.class)
                .list();

        assertThat(amended).as("a pending request at version 2 is seeded").isNotEmpty();
        for (UUID id : amended) {
            List<String> entryTypes = db.sql("SELECT entry_type FROM request_history WHERE request_id = ?")
                    .param(id)
                    .query(String.class)
                    .list();
            assertThat(entryTypes).as("history of %s", id).contains("CREATED", "AMENDED");
        }
    }

    @Test
    @DisplayName("an approved and a declined request are seeded, each recording who decided and when")
    void decidedRequestsRecordTheDecision() {
        assertThat(countWhere("status = 'APPROVED'")).as("approved requests").isPositive();
        assertThat(countWhere("status = 'DECLINED'")).as("declined requests").isPositive();
        assertThat(countWhere("status IN ('APPROVED', 'DECLINED')"
                + " AND (decided_by IS NULL OR decided_at IS NULL)")).isZero();
    }

    @Test
    @DisplayName("no request is decided by the person who raised it")
    void fourEyesHoldsInTheFixture() {
        assertThat(countWhere("decided_by = requested_by")).isZero();
        // The history is what the detail view shows, so the same must hold there.
        assertThat(db.sql("SELECT COUNT(*) FROM request_history h"
                        + " JOIN exception_request r ON r.id = h.request_id"
                        + " WHERE h.entry_type IN ('APPROVED', 'DECLINED') AND h.actor = r.requested_by")
                .query(Integer.class)
                .single()).isZero();
    }

    @Test
    @DisplayName("every discount is within the permitted range")
    void discountsAreWithinRange() {
        assertThat(countWhere("discount_bps < " + RequestLimits.MIN_DISCOUNT_BPS
                + " OR discount_bps > " + RequestLimits.MAX_DISCOUNT_BPS)).isZero();
    }

    private int countWhere(String condition) {
        return db.sql("SELECT COUNT(*) FROM exception_request WHERE " + condition)
                .query(Integer.class)
                .single();
    }
}
