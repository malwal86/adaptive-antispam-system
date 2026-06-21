package com.antispam.reputation.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.GatedReputation;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the reputation endpoints, standalone (no Spring context, no
 * database) so it runs everywhere. It pins what a client sees: the gated
 * {@code {authenticated, unauthenticated}} read shape (story 03.03), that an unseen
 * sender still returns the prior (200, not 404), that posting a signal routes the
 * accrual bucket from the request's auth tokens and returns the updated reputation, and
 * that a signal-less post is rejected.
 */
class SenderReputationControllerTest {

    private static final double ALPHA = 1.0;
    private static final double BETA = 1.0;

    private ReputationService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(ReputationService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new SenderReputationController(service)).build();
    }

    private static GatedReputation gated(BetaReputation authenticated, BetaReputation unauthenticated) {
        return new GatedReputation(authenticated, unauthenticated);
    }

    @Test
    void get_returns_the_gated_reputation_json_shape() throws Exception {
        // Earned 8/2 authenticated; only a neutral-capped unauthenticated view.
        when(service.gatedReputation("alice@example.com"))
                .thenReturn(gated(new BetaReputation(8, 2, ALPHA, BETA), new BetaReputation(0, 0, ALPHA, BETA)));

        mockMvc.perform(get("/senders/{id}/reputation", "alice@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderKey").value("alice@example.com"))
                .andExpect(jsonPath("$.authenticated.mean").value(0.75))
                .andExpect(jsonPath("$.authenticated.good").value(8.0))
                .andExpect(jsonPath("$.authenticated.bad").value(2.0))
                .andExpect(jsonPath("$.authenticated.n").value(10.0))
                .andExpect(jsonPath("$.authenticated.variance").value(27.0 / 1872.0))
                .andExpect(jsonPath("$.unauthenticated.mean").value(0.5))
                .andExpect(jsonPath("$.unauthenticated.n").value(0.0));
    }

    @Test
    void get_on_an_unseen_sender_returns_the_prior_not_404() throws Exception {
        when(service.gatedReputation("nobody@example.com"))
                .thenReturn(gated(new BetaReputation(0, 0, ALPHA, BETA), new BetaReputation(0, 0, ALPHA, BETA)));

        mockMvc.perform(get("/senders/{id}/reputation", "nobody@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated.mean").value(0.5))
                .andExpect(jsonPath("$.authenticated.n").value(0.0));
    }

    @Test
    void posting_without_auth_tokens_routes_to_the_unauthenticated_bucket() throws Exception {
        // No auth context -> the safe default: unauthenticated.
        when(service.record("alice@example.com", ReputationSignal.BAD, 1.0, "api", ReputationBucket.UNAUTHENTICATED))
                .thenReturn(gated(new BetaReputation(0, 0, ALPHA, BETA), new BetaReputation(0, 1, ALPHA, BETA)));

        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signal\":\"BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderKey").value("alice@example.com"))
                .andExpect(jsonPath("$.unauthenticated.bad").value(1.0));

        verify(service).record("alice@example.com", ReputationSignal.BAD, 1.0, "api", ReputationBucket.UNAUTHENTICATED);
    }

    @Test
    void posting_with_dmarc_pass_routes_to_the_authenticated_bucket() throws Exception {
        when(service.record(eq("alice@example.com"), eq(ReputationSignal.GOOD), eq(0.25), eq("feedback"),
                eq(ReputationBucket.AUTHENTICATED)))
                .thenReturn(gated(new BetaReputation(0.25, 0, ALPHA, BETA), new BetaReputation(0, 0, ALPHA, BETA)));

        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signal\":\"GOOD\",\"weight\":0.25,\"source\":\"feedback\","
                                + "\"spf\":\"pass\",\"dkim\":\"pass\",\"dmarc\":\"pass\"}"))
                .andExpect(status().isOk());

        verify(service).record("alice@example.com", ReputationSignal.GOOD, 0.25, "feedback",
                ReputationBucket.AUTHENTICATED);
    }

    @Test
    void posting_without_a_signal_is_rejected() throws Exception {
        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).record(Mockito.any(), Mockito.any(), Mockito.anyDouble(), Mockito.any(),
                Mockito.any());
    }
}
