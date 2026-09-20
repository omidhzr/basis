package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Two callers acting on the same version at the same time: exactly one wins.
 *
 * <p>This is the test that distinguishes a guard inside the UPDATE from a
 * read-then-compare in Java. Both forms pass every single-threaded test in this
 * suite; only this one fails when the guard is moved out of the statement,
 * because two callers would then both read version 1, both find it current,
 * and both write. Invariant 7 exists for this case, and this test is what
 * holds it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConcurrentAmendmentTest {

    private static final String REQUESTER = "rm-1";
    private static final String REVIEWER = "reviewer-1";
    private static final String REASON = "Retention case for a long-standing client";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExceptionRequestStore store;

    @Test
    @DisplayName("two amendments against the same version: one succeeds, the other is told it is stale")
    void twoAmendmentsRaceOnTheSameVersion() throws Exception {
        UUID id = createRequest();

        List<Integer> outcomes = runTogether(
                amendment(id, 40),
                amendment(id, 60));

        assertThat(outcomes)
                .as("exactly one amendment wins; the other is rejected as stale")
                .containsExactlyInAnyOrder(200, 409);

        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.version()).as("the winner moved the request on by exactly one").isEqualTo(2);
            assertThat(current.status()).isEqualTo(RequestStatus.PENDING);
        });

        assertThat(store.historyOf(id))
                .as("the rejected amendment appended nothing")
                .extracting(HistoryEntry::entryType)
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.AMENDED);
    }

    @Test
    @DisplayName("an amendment and a decision against the same version: one succeeds, the other is told it is stale")
    void anAmendmentAndADecisionRace() throws Exception {
        UUID id = createRequest();

        List<Integer> outcomes = runTogether(
                amendment(id, 40),
                approval(id));

        assertThat(outcomes).containsExactlyInAnyOrder(200, 409);

        assertThat(store.find(id)).hasValueSatisfying(current -> {
            // Whichever won, the request moved exactly one step: either it is
            // PENDING at version 2, or it is APPROVED at version 1. What it is
            // never is both.
            if (current.status() == RequestStatus.PENDING) {
                assertThat(current.version()).isEqualTo(2);
                assertThat(current.decidedBy()).isNull();
            } else {
                assertThat(current.status()).isEqualTo(RequestStatus.APPROVED);
                assertThat(current.version()).isEqualTo(1);
                assertThat(current.decidedBy()).isEqualTo(REVIEWER);
            }
        });

        assertThat(store.historyOf(id)).hasSize(2);
    }

    /**
     * Both calls are held at a barrier and released together, so they contend
     * on the same version rather than running in sequence.
     */
    private List<Integer> runTogether(Callable<Integer> first, Callable<Integer> second) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(atBarrier(startLine, first));
            Future<Integer> b = pool.submit(atBarrier(startLine, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<Integer> atBarrier(CyclicBarrier startLine, Callable<Integer> call) {
        return () -> {
            startLine.await(20, TimeUnit.SECONDS);
            return call.call();
        };
    }

    private Callable<Integer> amendment(UUID id, int discountBps) {
        return () -> mvc.perform(as(REQUESTER, UserRole.RELATIONSHIP_MANAGER, patch("/requests/" + id))
                        .content("{\"discountBps\":" + discountBps + ",\"version\":1}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private Callable<Integer> approval(UUID id) {
        return () -> mvc.perform(as(REVIEWER, UserRole.REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andReturn()
                .getResponse()
                .getStatus();
    }

    private UUID createRequest() throws Exception {
        String body = mvc.perform(as(REQUESTER, UserRole.RELATIONSHIP_MANAGER, post("/requests"))
                        .content("{\"applicationId\":\"APP-RACE\",\"discountBps\":25,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return UUID.fromString(json.readTree(body).path("id").asText());
    }

    private static MockHttpServletRequestBuilder as(
            String userId, UserRole role, MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User-Id", userId)
                .header("X-User-Role", role.name())
                .contentType(MediaType.APPLICATION_JSON);
    }
}
