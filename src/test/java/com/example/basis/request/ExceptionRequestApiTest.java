package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
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
 * The lifecycle through HTTP, against the real H2 schema. One test per
 * scenario in the spec delta; the repository is not mocked, because the
 * guarded statements are the thing being tested.
 *
 * <p>History is read through the store rather than through an endpoint: the
 * read endpoints belong to a later slice, and the append-only trail still has
 * to be asserted now.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ExceptionRequestApiTest {

    private static final String REQUESTER = "rm-1";
    private static final String REVIEWER = "reviewer-1";
    private static final String REASON = "Retention case for a long-standing client";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExceptionRequestStore store;

    @Autowired
    private JdbcClient db;

    // --- Acceptance criterion 1 -------------------------------------------

    @Test
    @DisplayName("a created request is PENDING at version 1 with a CREATED history entry")
    void createRecordsAPendingRequestAtVersionOne() throws Exception {
        MvcResult result = mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content("{\"applicationId\":\"APP-1\",\"discountBps\":25,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.requestedBy").value(REQUESTER))
                .andExpect(jsonPath("$.applicationId").value("APP-1"))
                .andExpect(jsonPath("$.discountBps").value(25))
                .andReturn();

        UUID id = idOf(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/requests/" + id);

        assertThat(store.historyOf(id)).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(EntryType.CREATED);
            assertThat(entry.version()).isEqualTo(1);
            assertThat(entry.actor()).isEqualTo(REQUESTER);
            assertThat(entry.payload()).contains("25").contains(REASON);
            assertThat(entry.occurredAt()).isNotNull();
        });
    }

    // --- Acceptance criterion 3 -------------------------------------------

    @Test
    @DisplayName("approving a version the request has moved past is rejected with 409")
    void decidingAStaleVersionIsRejected() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.discountBps").value(40));

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("version 2")))
                .andExpect(jsonPath("$.currentVersion").value(2));

        // The rejected decision left the request exactly as the amendment did.
        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(RequestStatus.PENDING);
            assertThat(current.version()).isEqualTo(2);
            assertThat(current.decidedBy()).isNull();
        });

        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.AMENDED);
    }

    @Test
    @DisplayName("an amendment against a stale version is rejected with 409")
    void amendingAStaleVersionIsRejected() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"reason\":\"A longer and more considered justification\",\"version\":1}"))
                .andExpect(status().isOk());

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.currentVersion").value(2));
    }

    @Test
    @DisplayName("a caller who did not raise the request may not amend it")
    void onlyTheRequesterMayAmend() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm("rm-2", patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("only be amended by the person who raised it")));

        assertThat(store.find(id)).hasValueSatisfying(current ->
                assertThat(current.version()).isEqualTo(1));
    }

    // --- Acceptance criterion 4 -------------------------------------------

    @Test
    @DisplayName("an approval records who decided and when")
    void approvalRecordsWhoAndWhen() throws Exception {
        UUID id = createRequest();

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1,\"note\":\"Within delegated authority\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(REVIEWER))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());

        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(RequestStatus.APPROVED);
            assertThat(current.decidedBy()).isEqualTo(REVIEWER);
            assertThat(current.decidedAt()).isNotNull();
        });

        assertThat(store.historyOf(id))
                .filteredOn(entry -> entry.entryType() == EntryType.APPROVED)
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.version()).isEqualTo(1);
                    assertThat(entry.actor()).isEqualTo(REVIEWER);
                    assertThat(entry.payload()).contains("Within delegated authority");
                });
    }

    @Test
    @DisplayName("a decline is terminal and records the decision")
    void declineIsRecorded() throws Exception {
        UUID id = createRequest();

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"DECLINE\",\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"))
                .andExpect(jsonPath("$.decidedBy").value(REVIEWER));

        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.DECLINED);
    }

    @Test
    @DisplayName("a caller without the reviewer role may not decide")
    void decidingRequiresTheReviewerRole() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm("rm-2", post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value(containsString("REVIEWER")));

        assertThat(store.find(id)).hasValueSatisfying(current ->
                assertThat(current.status()).isEqualTo(RequestStatus.PENDING));
    }

    @Test
    @DisplayName("a request accumulates one history entry per transition")
    void historyAccumulatesAcrossTheLifeOfARequest() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk());

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":2}"))
                .andExpect(status().isOk());

        List<HistoryEntry> history = store.historyOf(id);
        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.AMENDED, EntryType.APPROVED);
        assertThat(history).allSatisfy(entry -> {
            assertThat(entry.version()).isPositive();
            assertThat(entry.actor()).isNotBlank();
        });
        assertThat(history)
                .filteredOn(entry -> entry.entryType() == EntryType.AMENDED)
                .singleElement()
                .satisfies(entry -> assertThat(entry.version()).isEqualTo(2));
    }

    // --- Acceptance criterion 5 -------------------------------------------

    @Test
    @DisplayName("the requester may not decide their own request")
    void fourEyesIsEnforcedOnDecisions() throws Exception {
        UUID id = createRequest();

        // The role is not the point: this caller holds REVIEWER and is still refused.
        mvc.perform(asReviewer(REQUESTER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value(containsString("cannot be decided by the person who raised it")));

        assertThat(store.find(id)).hasValueSatisfying(current ->
                assertThat(current.status()).isEqualTo(RequestStatus.PENDING));
    }

    // --- Withdrawal --------------------------------------------------------

    @Test
    @DisplayName("the requester withdraws their own pending request")
    void requesterMayWithdraw() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm(REQUESTER, post("/requests/" + id + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));

        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.WITHDRAWN);
    }

    @Test
    @DisplayName("a caller who did not raise the request may not withdraw it")
    void onlyTheRequesterMayWithdraw() throws Exception {
        UUID id = createRequest();

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isForbidden());

        assertThat(store.find(id)).hasValueSatisfying(current ->
                assertThat(current.status()).isEqualTo(RequestStatus.PENDING));
    }

    // --- Terminal requests are immutable ----------------------------------

    @Test
    @DisplayName("a decided request can be neither amended nor decided again")
    void terminalRequestsAreImmutable() throws Exception {
        UUID id = createRequest();

        mvc.perform(asReviewer(REVIEWER, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isOk());

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("APPROVED")));

        mvc.perform(asReviewer("reviewer-2", post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"DECLINE\",\"version\":1}"))
                .andExpect(status().isConflict());

        // Neither rejection touched the stored request or its history.
        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.status()).isEqualTo(RequestStatus.APPROVED);
            assertThat(current.decidedBy()).isEqualTo(REVIEWER);
            assertThat(current.version()).isEqualTo(1);
        });
        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.APPROVED);
    }

    @Test
    @DisplayName("a withdrawn request can no longer be amended")
    void withdrawnRequestsAreImmutable() throws Exception {
        UUID id = createRequest();

        mvc.perform(asRm(REQUESTER, post("/requests/" + id + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("WITHDRAWN")));
    }

    // --- A rejected call leaves no trace ----------------------------------

    @Test
    @DisplayName("calls rejected with 400, 403 and 409 append no history entry")
    void rejectedCallsAppendNoHistory() throws Exception {
        UUID id = createRequest();

        // 400: a discount outside the permitted range.
        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":0,\"version\":1}"))
                .andExpect(status().isBadRequest());

        // 400: a field this endpoint does not accept -- every field beyond the
        // discount and the reason is immutable.
        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"applicationId\":\"APP-2\",\"version\":1}"))
                .andExpect(status().isBadRequest());

        // 403: a caller who did not raise the request.
        mvc.perform(asRm("rm-2", patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isForbidden());

        // 409: a version the request has moved past.
        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":7}"))
                .andExpect(status().isConflict());

        assertThat(typesInHistoryOf(id)).containsExactly(EntryType.CREATED);
        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.version()).isEqualTo(1);
            assertThat(current.discountBps()).isEqualTo(25);
        });
    }

    @Test
    @DisplayName("a rejected create stores nothing")
    void rejectedCreateStoresNothing() throws Exception {
        mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content("{\"applicationId\":\"APP-9\",\"discountBps\":201,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content("{\"applicationId\":\"APP-9\",\"discountBps\":25,\"reason\":\"too short\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content("{\"applicationId\":\" \",\"discountBps\":25,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest());

        // Queried directly: reading requests by application is a later slice,
        // and "nothing was stored" still has to be asserted now.
        assertThat(db.sql("SELECT COUNT(*) FROM exception_request WHERE application_id = ?")
                .param("APP-9")
                .query(Integer.class)
                .single())
                .isZero();
    }

    // --- Every non-2xx is a problem detail --------------------------------

    @Test
    @DisplayName("every non-2xx response carries a problem detail with a title and an actionable detail")
    void everyRejectionIsAProblemDetail() throws Exception {
        UUID id = createRequest();

        assertProblemDetail(mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":0,\"version\":1}"))
                .andExpect(status().isBadRequest()).andReturn());

        assertProblemDetail(mvc.perform(asRm("rm-2", patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isForbidden()).andReturn());

        assertProblemDetail(mvc.perform(asRm(REQUESTER, patch("/requests/" + UUID.randomUUID()))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isNotFound()).andReturn());

        assertProblemDetail(mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":7}"))
                .andExpect(status().isConflict()).andReturn());

        // A missing identity header is a rejection like any other.
        assertProblemDetail(mvc.perform(post("/requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"applicationId\":\"APP-1\",\"discountBps\":25,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isBadRequest()).andReturn());
    }

    private void assertProblemDetail(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentType())
                .as("a rejection is an RFC 7807 problem detail")
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode problem = json.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("title").asText())
                .as("a problem detail names what went wrong")
                .isNotBlank();
        assertThat(problem.path("detail").asText())
                .as("a problem detail says something a person could act on")
                .isNotBlank()
                .hasSizeGreaterThan(20);
        assertThat(problem.path("status").asInt()).isEqualTo(result.getResponse().getStatus());
    }

    // --- Helpers -----------------------------------------------------------

    private UUID createRequest() throws Exception {
        return idOf(mvc.perform(asRm(REQUESTER, post("/requests"))
                        .content("{\"applicationId\":\"APP-1\",\"discountBps\":25,\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private List<EntryType> typesInHistoryOf(UUID id) {
        return store.historyOf(id).stream().map(HistoryEntry::entryType).toList();
    }

    /** Identity stands in for JWT claims; see the note on the controller. */
    private static MockHttpServletRequestBuilder asRm(String userId, MockHttpServletRequestBuilder builder) {
        return as(userId, UserRole.RELATIONSHIP_MANAGER, builder);
    }

    private static MockHttpServletRequestBuilder asReviewer(String userId, MockHttpServletRequestBuilder builder) {
        return as(userId, UserRole.REVIEWER, builder);
    }

    private static MockHttpServletRequestBuilder as(
            String userId, UserRole role, MockHttpServletRequestBuilder builder) {
        return builder
                .header("X-User-Id", userId)
                .header("X-User-Role", role.name())
                .contentType(MediaType.APPLICATION_JSON);
    }
}
