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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The replay contract on {@code POST /requests}, through HTTP and against the
 * real H2 schema -- acceptance criterion 2.
 *
 * <p>Each test works against an application identifier of its own, because the
 * schema is shared across the suite and "exactly one request was created" is
 * counted rather than inferred. Requests and records are read back through the
 * store and the database rather than through an endpoint: a replay must return
 * the stored response and create nothing new, and both halves of that are
 * claims about what is in the tables rather than about what a read serves.
 */
@SpringBootTest
@AutoConfigureMockMvc
class IdempotentCreateTest {

    private static final String REQUESTER = "rm-1";
    private static final String OTHER_REQUESTER = "rm-2";
    private static final String REASON = "Retention case for a long-standing client";
    private static final String OTHER_REASON = "Competitor offer matched at the branch request";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExceptionRequestStore store;

    @Autowired
    private JdbcClient db;

    // --- Acceptance criterion 2 -------------------------------------------

    @Test
    @DisplayName("a retry with the same key and body returns the original response and creates nothing")
    void aRetryReplaysTheOriginalResponse() throws Exception {
        String application = "APP-IDEM-REPLAY";
        String key = UUID.randomUUID().toString();

        MvcResult first = mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = idOf(first);

        MvcResult retry = mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(retry.getResponse().getContentAsString())
                .as("the replay carries the body the first call returned, unchanged")
                .isEqualTo(first.getResponse().getContentAsString());
        assertThat(retry.getResponse().getHeader("Location"))
                .as("the replay names the request the first call created")
                .isEqualTo("/requests/" + id);
        assertThat(idOf(retry)).isEqualTo(id);

        assertThat(requestsFor(application))
                .as("the retry created no second request")
                .isEqualTo(1);
        assertThat(typesInHistoryOf(id))
                .as("the retry appended nothing beyond the original CREATED entry")
                .containsExactly(EntryType.CREATED);
    }

    @Test
    @DisplayName("a retry of a request that has since been amended still replays the original response")
    void aRetryOfAnAmendedRequestStillReplays() throws Exception {
        String application = "APP-IDEM-AMENDED";
        String key = UUID.randomUUID().toString();

        MvcResult first = mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn();
        UUID id = idOf(first);

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk());

        // The body is the one that was first submitted, so this is a replay.
        // It is a conflict only if the retry is compared against the request as
        // it stands now rather than against the response the create returned.
        mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .isEqualTo(first.getResponse().getContentAsString()));

        assertThat(requestsFor(application)).isEqualTo(1);
        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.AMENDED);
    }

    // --- The key is required ----------------------------------------------

    @Test
    @DisplayName("a create with no key, a blank key or an over-length key is rejected and stores nothing")
    void aCreateWithoutAUsableKeyIsRejected() throws Exception {
        String application = "APP-IDEM-NOKEY";

        mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content(body(application, 25, REASON)))
                .andExpect(status().isBadRequest());

        mvc.perform(create(REQUESTER, "   ", application, 25, REASON))
                .andExpect(status().isBadRequest());

        mvc.perform(create(REQUESTER, "", application, 25, REASON))
                .andExpect(status().isBadRequest());

        String tooLong = "k".repeat(129);
        mvc.perform(create(REQUESTER, tooLong, application, 25, REASON))
                .andExpect(status().isBadRequest());

        assertThat(requestsFor(application))
                .as("a create carrying no usable key records nothing")
                .isZero();
        assertThat(recordsFor(tooLong)).isZero();
    }

    // --- A reused key carrying a different body ---------------------------

    @Test
    @DisplayName("a key reused for a different application, discount or reason is rejected with 422")
    void aKeyReusedForADifferentRequestIsRejected() throws Exception {
        String application = "APP-IDEM-REUSED";
        String key = UUID.randomUUID().toString();

        UUID id = idOf(mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn());

        // Each field in turn, so a comparison that drops one fails here rather
        // than quietly accepting a create it should have refused.
        mvc.perform(create(REQUESTER, key, "APP-IDEM-REUSED-OTHER", 25, REASON))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(create(REQUESTER, key, application, 40, REASON))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(create(REQUESTER, key, application, 25, OTHER_REASON))
                .andExpect(status().isUnprocessableEntity());

        assertThat(requestsFor(application)).isEqualTo(1);
        assertThat(requestsFor("APP-IDEM-REUSED-OTHER"))
                .as("the rejected create raised nothing under its own application either")
                .isZero();
        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.applicationId()).isEqualTo(application);
            assertThat(current.discountBps()).isEqualTo(25);
            assertThat(current.reason()).isEqualTo(REASON);
            assertThat(current.version()).isEqualTo(1);
            assertThat(current.status()).isEqualTo(RequestStatus.PENDING);
        });
    }

    // --- The key belongs to its caller ------------------------------------

    @Test
    @DisplayName("the same key from a different caller creates a request of its own")
    void aKeyIsScopedToItsCaller() throws Exception {
        String application = "APP-IDEM-CALLER";
        String key = UUID.randomUUID().toString();

        UUID first = idOf(mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn());

        UUID second = idOf(mvc.perform(create(OTHER_REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn());

        assertThat(second)
                .as("the second caller was not given the first caller request")
                .isNotEqualTo(first);
        assertThat(requestsFor(application)).isEqualTo(2);
        assertThat(store.find(second)).hasValueSatisfying(current ->
                assertThat(current.requestedBy()).isEqualTo(OTHER_REQUESTER));
    }

    // --- Two retries at once ----------------------------------------------

    @Test
    @DisplayName("two retries of one create submitted together produce exactly one request")
    void twoRetriesOfOneCreateRace() throws Exception {
        String application = "APP-IDEM-RACE";
        String key = UUID.randomUUID().toString();

        List<MvcResult> outcomes = runTogether(
                submitCreate(key, application),
                submitCreate(key, application));

        assertThat(outcomes)
                .as("neither caller was handed a server error")
                .allSatisfy(result -> assertThat(result.getResponse().getStatus()).isLessThan(500));

        assertThat(requestsFor(application))
                .as("only one of the two retries created a request")
                .isEqualTo(1);

        UUID id = onlyRequestFor(application);
        assertThat(typesInHistoryOf(id))
                .as("exactly one CREATED entry exists for it")
                .containsExactly(EntryType.CREATED);

        assertThat(outcomes)
                .as("a caller answered 2xx was told about the request that was created")
                .filteredOn(result -> result.getResponse().getStatus() < 300)
                .allSatisfy(result -> assertThat(idOf(result)).isEqualTo(id));
    }

    // --- A rejected create leaves no trace --------------------------------

    @Test
    @DisplayName("a create rejected with 400 or 422 appends no history and stores no idempotency record")
    void aRejectedCreateStoresNoRecord() throws Exception {
        String application = "APP-IDEM-REJECTED";

        // 400: the body fails validation, so the key is never taken.
        String unusedKey = UUID.randomUUID().toString();
        mvc.perform(create(REQUESTER, unusedKey, application, 201, REASON))
                .andExpect(status().isBadRequest());
        assertThat(recordsFor(unusedKey))
                .as("a rejected create did not claim its key")
                .isZero();

        // 422: the key is taken by the create that succeeded, and by nothing else.
        String key = UUID.randomUUID().toString();
        UUID id = idOf(mvc.perform(create(REQUESTER, key, application, 25, REASON))
                .andExpect(status().isCreated())
                .andReturn());

        mvc.perform(create(REQUESTER, key, application, 40, OTHER_REASON))
                .andExpect(status().isUnprocessableEntity());

        assertThat(recordsFor(key))
                .as("the refused create stored no second record against the key")
                .isEqualTo(1);
        assertThat(requestsFor(application)).isEqualTo(1);
        assertThat(typesInHistoryOf(id)).containsExactly(EntryType.CREATED);
    }

    // --- Helpers -----------------------------------------------------------

    /**
     * Both calls are held at a barrier and released together, so they contend
     * on the same key rather than running in sequence -- as
     * {@link ConcurrentAmendmentTest} does for the version guard.
     */
    private List<MvcResult> runTogether(Callable<MvcResult> first, Callable<MvcResult> second) throws Exception {
        CyclicBarrier startLine = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> a = pool.submit(atBarrier(startLine, first));
            Future<MvcResult> b = pool.submit(atBarrier(startLine, second));
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private static Callable<MvcResult> atBarrier(CyclicBarrier startLine, Callable<MvcResult> call) {
        return () -> {
            startLine.await(20, TimeUnit.SECONDS);
            return call.call();
        };
    }

    private Callable<MvcResult> submitCreate(String key, String application) {
        return () -> mvc.perform(create(REQUESTER, key, application, 25, REASON)).andReturn();
    }

    private int requestsFor(String application) {
        return db.sql("SELECT COUNT(*) FROM exception_request WHERE application_id = ?")
                .param(application)
                .query(Integer.class)
                .single();
    }

    private UUID onlyRequestFor(String application) {
        return db.sql("SELECT id FROM exception_request WHERE application_id = ?")
                .param(application)
                .query(UUID.class)
                .single();
    }

    private int recordsFor(String key) {
        return db.sql("SELECT COUNT(*) FROM idempotency_record WHERE \"key\" = ?")
                .param(key)
                .query(Integer.class)
                .single();
    }

    private List<EntryType> typesInHistoryOf(UUID id) {
        return store.historyOf(id).stream().map(HistoryEntry::entryType).toList();
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private static String body(String application, int discountBps, String reason) {
        return "{\"applicationId\":\"" + application + "\",\"discountBps\":" + discountBps
                + ",\"reason\":\"" + reason + "\"}";
    }

    private MockHttpServletRequestBuilder create(
            String userId, String key, String application, int discountBps, String reason) {
        return asRm(userId, post("/requests"))
                .header("Idempotency-Key", key)
                .content(body(application, discountBps, reason));
    }

    /** Identity stands in for JWT claims; see the note on the controller. */
    private static MockHttpServletRequestBuilder asRm(String userId, MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User-Id", userId)
                .header("X-User-Role", UserRole.RELATIONSHIP_MANAGER.name())
                .contentType(MediaType.APPLICATION_JSON);
    }
}
