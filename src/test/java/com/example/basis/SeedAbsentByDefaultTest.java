package com.example.basis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The demonstration seed must not reach the database the rest of the suite
 * shares. Spring Boot runs a classpath-root {@code data.sql} under every profile
 * when {@code spring.sql.init.mode=embedded}, so moving the seed there would
 * fail unrelated tests that count rows and assert what a queue does not
 * contain. This fails first, and says why.
 *
 * <p>Other tests' requests are legitimately in the same table, so the assertion
 * is about the seed's fixed identifiers and nothing else.
 */
@SpringBootTest
class SeedAbsentByDefaultTest {

    // The seed's request identifiers. Fixed on purpose -- see the seed's own
    // comment -- so that a test can name them.
    private static final List<UUID> SEEDED_REQUEST_IDS = List.of(
            UUID.fromString("5eed0000-0000-4000-8000-000000000001"),
            UUID.fromString("5eed0000-0000-4000-8000-000000000002"),
            UUID.fromString("5eed0000-0000-4000-8000-000000000003"),
            UUID.fromString("5eed0000-0000-4000-8000-000000000004"),
            UUID.fromString("5eed0000-0000-4000-8000-000000000005"));

    @Autowired
    private JdbcClient db;

    @Test
    @DisplayName("under the default profile none of the seeded requests is in the shared database")
    void seededRequestsAreAbsent() {
        assertThat(count("exception_request", "id")).isZero();
    }

    @Test
    @DisplayName("under the default profile none of the seeded history is in the shared database")
    void seededHistoryIsAbsent() {
        // History rows are checked by the request they belong to, so this holds
        // whatever identifiers the seed gives its own history entries.
        assertThat(count("request_history", "request_id")).isZero();
    }

    private int count(String table, String column) {
        int found = 0;
        for (UUID id : SEEDED_REQUEST_IDS) {
            found += db.sql("SELECT COUNT(*) FROM " + table + " WHERE " + column + " = ?")
                    .param(id)
                    .query(Integer.class)
                    .single();
        }
        return found;
    }
}
