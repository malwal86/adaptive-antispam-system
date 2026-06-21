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
 * database) so it runs everywhere. It pins what a client sees: the
 * {@code {mean, variance, good, bad, n}} read shape, that an unseen sender still
 * returns the prior (200, not 404), that posting a signal forwards it to the service
 * and returns the updated reputation, and that a signal-less post is rejected.
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

    @Test
    void get_returns_the_reputation_json_shape() throws Exception {
        when(service.currentReputation("alice@example.com"))
                .thenReturn(new BetaReputation(8, 2, ALPHA, BETA));

        mockMvc.perform(get("/senders/{id}/reputation", "alice@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderKey").value("alice@example.com"))
                .andExpect(jsonPath("$.mean").value(0.75))
                .andExpect(jsonPath("$.good").value(8.0))
                .andExpect(jsonPath("$.bad").value(2.0))
                .andExpect(jsonPath("$.n").value(10.0))
                .andExpect(jsonPath("$.variance").value(27.0 / 1872.0));
    }

    @Test
    void get_on_an_unseen_sender_returns_the_prior_not_404() throws Exception {
        when(service.currentReputation("nobody@example.com"))
                .thenReturn(new BetaReputation(0, 0, ALPHA, BETA));

        mockMvc.perform(get("/senders/{id}/reputation", "nobody@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mean").value(0.5))
                .andExpect(jsonPath("$.n").value(0.0));
    }

    @Test
    void posting_a_signal_records_it_and_returns_the_updated_reputation() throws Exception {
        when(service.record("alice@example.com", ReputationSignal.BAD, 1.0, "api"))
                .thenReturn(new BetaReputation(0, 1, ALPHA, BETA));

        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signal\":\"BAD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.senderKey").value("alice@example.com"))
                .andExpect(jsonPath("$.bad").value(1.0));

        verify(service).record("alice@example.com", ReputationSignal.BAD, 1.0, "api");
    }

    @Test
    void posting_passes_through_an_explicit_weight_and_source() throws Exception {
        when(service.record(eq("alice@example.com"), eq(ReputationSignal.GOOD), eq(0.25), eq("feedback")))
                .thenReturn(new BetaReputation(0.25, 0, ALPHA, BETA));

        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"signal\":\"GOOD\",\"weight\":0.25,\"source\":\"feedback\"}"))
                .andExpect(status().isOk());

        verify(service).record("alice@example.com", ReputationSignal.GOOD, 0.25, "feedback");
    }

    @Test
    void posting_without_a_signal_is_rejected() throws Exception {
        mockMvc.perform(post("/senders/{id}/reputation/events", "alice@example.com")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).record(Mockito.any(), Mockito.any(), Mockito.anyDouble(), Mockito.any());
    }
}
