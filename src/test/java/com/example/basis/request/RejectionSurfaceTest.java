package com.example.basis.request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * The two rejections the brief leaves undefined and the business has settled:
 * an identity longer than the service can store, and an amendment that carries
 * nothing to amend. Both are asserted through HTTP against the real H2 schema,
 * because both claims are about what was stored as much as what was answered.
 *
 * <p>Each test works against an application identifier of its own: the schema
 * is shared across the suite, so "nothing was created" is counted rather than
 * inferred.
 */
@SpringBootTest
@AutoConfigureMockMvc
class RejectionSurfaceTest {

    private static final String REQUESTER = "rm-1";
    private static final String REASON = "Retention case for a long-standing client";
    private static final String LONGER_REASON = "Competitor offer matched at the branch request";

    /**
     * The bound is the width of the column the identity is stored in, and the
     * constant the service names it by. Spelled from the constant so that
     * moving the bound moves both sides of this test with it.
     */
    private static final String AT_THE_BOUND = "u".repeat(RequestLimits.MAX_IDENTITY_LENGTH);
    private static final String OVER_THE_BOUND = "u".repeat(RequestLimits.MAX_IDENTITY_LENGTH + 1);
    private static final String REVIEWER_AT_THE_BOUND = "v".repeat(RequestLimits.MAX_IDENTITY_LENGTH);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ExceptionRequestStore store;

    @Autowired
    private JdbcClient db;

    // --- The identity headers are bounded ---------------------------------

    @Test
    @DisplayName("an identity longer than the bound is refused on every endpoint, and changes nothing")
    void identityOverTheBoundIsRefusedEverywhere() throws Exception {
        UUID id = createRequest("APP-ID-1");

        mvc.perform(withKey(asRm(OVER_THE_BOUND, post("/requests")))
                        .content(createBody("APP-ID-REFUSED")))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value(containsString("X-User-Id")));

        mvc.perform(asRm(OVER_THE_BOUND, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(asReviewer(OVER_THE_BOUND, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        mvc.perform(asRm(OVER_THE_BOUND, post("/requests/" + id + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));

        // The refused create stored no request, and the three refused writes
        // left the existing one exactly as it was.
        assertThat(countRequestsFor("APP-ID-REFUSED")).isZero();
        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.version()).isEqualTo(1);
            assertThat(current.status()).isEqualTo(RequestStatus.PENDING);
            assertThat(current.decidedBy()).isNull();
        });
        assertThat(typesInHistoryOf(id)).containsExactly(EntryType.CREATED);
    }

    @Test
    @DisplayName("a refused identity reaches no idempotency record either")
    void identityOverTheBoundStoresNoIdempotencyRecord() throws Exception {
        String key = UUID.randomUUID().toString();

        mvc.perform(asRm(OVER_THE_BOUND, post("/requests"))
                        .header("Idempotency-Key", key)
                        .content(createBody("APP-ID-NO-RECORD")))
                .andExpect(status().isBadRequest());

        assertThat(db.sql("SELECT COUNT(*) FROM idempotency_record WHERE \"key\" = ?")
                        .param(key)
                        .query(Integer.class)
                        .single())
                .isZero();
        assertThat(countRequestsFor("APP-ID-NO-RECORD")).isZero();
    }

    @Test
    @DisplayName("an identity exactly at the bound is accepted on every endpoint")
    void identityAtTheBoundIsAccepted() throws Exception {
        MvcResult created = mvc.perform(withKey(asRm(AT_THE_BOUND, post("/requests")))
                        .content(createBody("APP-ID-2")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.requestedBy").value(AT_THE_BOUND))
                .andReturn();
        UUID id = idOf(created);

        mvc.perform(asRm(AT_THE_BOUND, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        mvc.perform(asReviewer(REVIEWER_AT_THE_BOUND, post("/requests/" + id + "/decision"))
                        .content("{\"decision\":\"APPROVE\",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy").value(REVIEWER_AT_THE_BOUND));

        // Withdrawal is the fourth endpoint and needs a request of its own,
        // the one above being terminal.
        UUID toWithdraw = idOf(mvc.perform(withKey(asRm(AT_THE_BOUND, post("/requests")))
                        .content(createBody("APP-ID-3")))
                .andExpect(status().isCreated())
                .andReturn());

        mvc.perform(asRm(AT_THE_BOUND, post("/requests/" + toWithdraw + "/withdrawal"))
                        .content("{\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
    }

    // --- An amendment carries something to amend --------------------------

    @Test
    @DisplayName("an amendment carrying only the version is refused, and the request is untouched")
    void amendmentWithNothingToAmendIsRefused() throws Exception {
        UUID id = createRequest("APP-AMEND-1");

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"version\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").isNotEmpty())
                // Written for the caller: the detail names the two values an
                // amendment may carry, rather than a field that failed.
                .andExpect(jsonPath("$.detail").value(containsString("discount")))
                .andExpect(jsonPath("$.detail").value(containsString("reason")));

        assertThat(store.find(id)).hasValueSatisfying(current -> {
            assertThat(current.version()).isEqualTo(1);
            assertThat(current.discountBps()).isEqualTo(25);
            assertThat(current.reason()).isEqualTo(REASON);
        });
        assertThat(typesInHistoryOf(id)).containsExactly(EntryType.CREATED);
    }

    @Test
    @DisplayName("an amendment carrying either field alone still succeeds")
    void amendmentCarryingOneFieldSucceeds() throws Exception {
        UUID id = createRequest("APP-AMEND-2");

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"discountBps\":40,\"version\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.discountBps").value(40))
                .andExpect(jsonPath("$.reason").value(REASON));

        mvc.perform(asRm(REQUESTER, patch("/requests/" + id))
                        .content("{\"reason\":\"" + LONGER_REASON + "\",\"version\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(3))
                .andExpect(jsonPath("$.discountBps").value(40))
                .andExpect(jsonPath("$.reason").value(LONGER_REASON));

        // One entry per amendment, both recorded. The order history comes back
        // in is the read slice's claim, not this one's.
        assertThat(typesInHistoryOf(id))
                .containsExactlyInAnyOrder(EntryType.CREATED, EntryType.AMENDED, EntryType.AMENDED);
    }

    // --- Helpers -----------------------------------------------------------

    private UUID createRequest(String applicationId) throws Exception {
        return idOf(mvc.perform(withKey(asRm(REQUESTER, post("/requests")))
                        .content(createBody(applicationId)))
                .andExpect(status().isCreated())
                .andReturn());
    }

    private static String createBody(String applicationId) {
        return "{\"applicationId\":\"" + applicationId + "\",\"discountBps\":25,\"reason\":\"" + REASON + "\"}";
    }

    private UUID idOf(MvcResult result) throws Exception {
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText());
    }

    private List<EntryType> typesInHistoryOf(UUID id) {
        return store.historyOf(id).stream().map(HistoryEntry::entryType).toList();
    }

    private int countRequestsFor(String applicationId) {
        // Counted in the database: reading requests by application is a later
        // slice, and "nothing was stored" has to be asserted now.
        return db.sql("SELECT COUNT(*) FROM exception_request WHERE application_id = ?")
                .param(applicationId)
                .query(Integer.class)
                .single();
    }

    private static MockHttpServletRequestBuilder withKey(MockHttpServletRequestBuilder builder) {
        return builder.header("Idempotency-Key", UUID.randomUUID().toString());
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
