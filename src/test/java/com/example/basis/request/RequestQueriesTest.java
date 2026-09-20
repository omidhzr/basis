package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The read side through HTTP, against the real H2 schema. One test per
 * scenario in the spec delta.
 *
 * <p>The schema is shared across the suite, so each test works with users and
 * application identifiers of its own and asserts what a queue must and must
 * not contain rather than its exact contents -- another test's requests are
 * legitimately in the same table.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RequestQueriesTest {

    private static final String REASON = "Retention case for a long-standing client";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    // --- Reading one request ----------------------------------------------

    @Test
    @DisplayName("a request reads back with its current values and its history")
    void readsARequestWithItsHistory() throws Exception {
        UUID id = create("read-rm", "APP-READ-1", 25);

        mvc.perform(asRm("read-rm", get("/requests/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.request.id").value(id.toString()))
                .andExpect(jsonPath("$.request.applicationId").value("APP-READ-1"))
                .andExpect(jsonPath("$.request.discountBps").value(25))
                .andExpect(jsonPath("$.request.status").value("PENDING"))
                .andExpect(jsonPath("$.request.version").value(1))
                .andExpect(jsonPath("$.request.requestedBy").value("read-rm"))
                .andExpect(jsonPath("$.history.length()").value(1))
                .andExpect(jsonPath("$.history[0].entryType").value("CREATED"))
                .andExpect(jsonPath("$.history[0].version").value(1))
                .andExpect(jsonPath("$.history[0].actor").value("read-rm"))
                .andExpect(jsonPath("$.history[0].occurredAt").isNotEmpty())
                // The payload is the object it was stored as, not a string the
                // client would have to parse out of a parsed document.
                .andExpect(jsonPath("$.history[0].payload.discountBps").value(25))
                .andExpect(jsonPath("$.history[0].payload.reason").value(REASON));
    }

    @Test
    @DisplayName("an identifier no request has is 404 with a problem detail")
    void unknownIdentifierIsNotFound() throws Exception {
        assertProblemDetail(mvc.perform(asRm("read-rm", get("/requests/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andReturn());
    }

    @Test
    @DisplayName("history is returned newest first, each entry naming the version it concerned")
    void historyIsReturnedNewestFirst() throws Exception {
        UUID id = create("order-rm", "APP-ORDER-1", 25);

        mvc.perform(asRm("order-rm", patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk());

        mvc.perform(asReviewer("order-reviewer", post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":2,\"note\":\"Within delegated authority\"}"))
                .andExpect(status().isOk());

        mvc.perform(asRm("order-rm", get("/requests/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history.length()").value(3))
                // The decision, then the amendment it was recorded against,
                // then the creation.
                .andExpect(jsonPath("$.history[0].entryType").value("APPROVED"))
                .andExpect(jsonPath("$.history[0].version").value(2))
                .andExpect(jsonPath("$.history[0].actor").value("order-reviewer"))
                .andExpect(jsonPath("$.history[1].entryType").value("AMENDED"))
                .andExpect(jsonPath("$.history[1].version").value(2))
                .andExpect(jsonPath("$.history[1].actor").value("order-rm"))
                .andExpect(jsonPath("$.history[2].entryType").value("CREATED"))
                .andExpect(jsonPath("$.history[2].version").value(1));
    }

    // --- The queue ---------------------------------------------------------

    @Test
    @DisplayName("a reviewer is offered pending requests raised by others and never their own")
    void reviewerQueueExcludesTheirOwnRequests() throws Exception {
        UUID raisedByAnother = create("queue-rm", "APP-QUEUE-1", 25);
        UUID raisedByTheReviewer = create("queue-reviewer", "APP-QUEUE-2", 30);

        List<String> queue = idsIn(mvc.perform(asReviewer("queue-reviewer", get("/requests")))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(queue).contains(raisedByAnother.toString());
        // Four-eyes: the API does not return it, so no client can offer to
        // decide it.
        assertThat(queue).doesNotContain(raisedByTheReviewer.toString());
    }

    @Test
    @DisplayName("a requester sees their own requests in every state and no one else's")
    void requesterQueueHoldsTheirOwnInEveryState() throws Exception {
        UUID pending = create("own-rm", "APP-OWN-1", 25);
        UUID withdrawn = create("own-rm", "APP-OWN-2", 30);
        UUID raisedByAnother = create("other-rm", "APP-OWN-3", 35);

        withdraw("own-rm", withdrawn);

        List<String> queue = idsIn(mvc.perform(asRm("own-rm", get("/requests")))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(queue).contains(pending.toString(), withdrawn.toString());
        assertThat(queue).doesNotContain(raisedByAnother.toString());
    }

    @Test
    @DisplayName("a status narrows the requester's queue to their requests in that status")
    void statusNarrowsTheRequestersQueue() throws Exception {
        UUID pending = create("narrow-rm", "APP-NARROW-1", 25);
        UUID withdrawn = create("narrow-rm", "APP-NARROW-2", 30);

        withdraw("narrow-rm", withdrawn);

        List<String> queue = idsIn(mvc.perform(asRm("narrow-rm", get("/requests?status=WITHDRAWN")))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(queue).contains(withdrawn.toString());
        assertThat(queue).doesNotContain(pending.toString());
    }

    @Test
    @DisplayName("a status widens the reviewer's queue to that status, still excluding their own")
    void statusWidensTheReviewersQueue() throws Exception {
        UUID raisedByAnother = create("widen-rm", "APP-WIDEN-1", 25);
        UUID raisedByTheReviewer = create("widen-reviewer", "APP-WIDEN-2", 30);

        approve("widen-reviewer", raisedByAnother);
        // A second reviewer, because four-eyes stops the first deciding a
        // request they raised themselves.
        approve("widen-reviewer-2", raisedByTheReviewer);

        List<String> queue = idsIn(mvc.perform(asReviewer("widen-reviewer", get("/requests?status=APPROVED")))
                .andExpect(status().isOk())
                .andReturn());

        assertThat(queue).contains(raisedByAnother.toString());
        assertThat(queue).doesNotContain(raisedByTheReviewer.toString());
    }

    @Test
    @DisplayName("a status outside the permitted set is 400 with a problem detail")
    void anUnknownStatusIsRefused() throws Exception {
        assertProblemDetail(mvc.perform(asRm("narrow-rm", get("/requests?status=IN_REVIEW")))
                .andExpect(status().isBadRequest())
                .andReturn());
    }

    // --- Acceptance criterion 6: the approved exception --------------------

    @Test
    @DisplayName("an application's approved exception carries the discount and the decision")
    void approvedExceptionIsRetrievableForAnApplication() throws Exception {
        UUID id = create("consume-rm", "APP-CONSUME-1", 25);
        approve("consume-reviewer", id);

        mvc.perform(asRm("consume-rm", get("/applications/APP-CONSUME-1/approved-exception")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applicationId").value("APP-CONSUME-1"))
                .andExpect(jsonPath("$.requestId").value(id.toString()))
                .andExpect(jsonPath("$.discountBps").value(25))
                .andExpect(jsonPath("$.requestedBy").value("consume-rm"))
                .andExpect(jsonPath("$.decidedBy").value("consume-reviewer"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty());
    }

    @Test
    @DisplayName("where an application has more than one approval, the most recently decided one applies")
    void theMostRecentApprovalApplies() throws Exception {
        UUID earlier = create("super-rm", "APP-SUPER-1", 25);
        approve("super-reviewer", earlier);
        waitForTheClockToTick();

        UUID later = create("super-rm", "APP-SUPER-1", 60);
        approve("super-reviewer", later);

        mvc.perform(asRm("super-rm", get("/applications/APP-SUPER-1/approved-exception")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(later.toString()))
                .andExpect(jsonPath("$.discountBps").value(60));
    }

    @Test
    @DisplayName("an application with no approved request is 404 with a problem detail")
    void anApplicationWithNoApprovalIsNotFound() throws Exception {
        create("none-rm", "APP-NONE-1", 25);
        UUID declined = create("none-rm", "APP-NONE-1", 30);
        UUID withdrawn = create("none-rm", "APP-NONE-1", 35);

        mvc.perform(asReviewer("none-reviewer", post("/requests/" + declined + "/decision"))
                        .content("{\"decision\":\"DECLINE\",\"version\":1}"))
                .andExpect(status().isOk());
        withdraw("none-rm", withdrawn);

        // Pending, declined and withdrawn requests are not an exception the
        // mortgage process may apply, so an application holding only those
        // answers as one holding nothing.
        assertProblemDetail(mvc.perform(asRm("none-rm", get("/applications/APP-NONE-1/approved-exception")))
                .andExpect(status().isNotFound())
                .andReturn());

        assertProblemDetail(mvc.perform(asRm("none-rm", get("/applications/APP-UNKNOWN/approved-exception")))
                .andExpect(status().isNotFound())
                .andReturn());
    }

    // --- Helpers -----------------------------------------------------------

    private UUID create(String requester, String applicationId, int discountBps) throws Exception {
        MvcResult result = mvc.perform(asRm(requester, post("/requests"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .content("{\"applicationId\":\"" + applicationId + "\",\"discountBps\":" + discountBps
                                + ",\"reason\":\"" + REASON + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private void approve(String reviewer, UUID id) throws Exception {
        mvc.perform(asReviewer(reviewer, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isOk());
    }

    private void withdraw(String requester, UUID id) throws Exception {
        mvc.perform(asRm(requester, post("/requests/" + id + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isOk());
    }

    /**
     * Two approvals a fraction of a millisecond apart share a {@code
     * decided_at} on a host whose clock ticks in milliseconds, and which of
     * them is "most recent" is then a coin-toss -- a tie design.md records as
     * accepted rather than guarded, because two approvals on one application
     * are minutes apart in practice and the guard that would fix it properly
     * is the SUPERSEDED status this service deliberately does not model.
     * Waiting for the clock to move keeps this test an assertion about the
     * rule rather than about the tie.
     */
    private static void waitForTheClockToTick() {
        Instant start = Instant.now();
        while (!Instant.now().isAfter(start)) {
            Thread.onSpinWait();
        }
    }

    private List<String> idsIn(MvcResult result) throws Exception {
        JsonNode queue = json.readTree(result.getResponse().getContentAsString());
        return queue.findValuesAsText("id");
    }

    private void assertProblemDetail(MvcResult result) throws Exception {
        assertThat(result.getResponse().getContentType())
                .as("a rejection is an RFC 7807 problem detail")
                .startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        JsonNode problem = json.readTree(result.getResponse().getContentAsString());
        assertThat(problem.path("title").asText()).isNotBlank();
        assertThat(problem.path("detail").asText())
                .as("a problem detail says something a person could act on")
                .isNotBlank();
        assertThat(problem.path("status").asInt()).isEqualTo(result.getResponse().getStatus());
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
