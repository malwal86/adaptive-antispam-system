package com.antispam.experiment.shadow.web;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.decision.policy.PolicyRepository;
import com.antispam.experiment.shadow.ShadowDecisionRepository;
import com.antispam.experiment.shadow.ShadowDecisionRepository.AgreementStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the shadow endpoints, standalone (no Spring context, no DB) so it runs
 * everywhere. Pins the client-facing outcomes: designating a shadow policy returns the version (and
 * an unknown one is a 400, not a 500), clearing returns 204, and the agreement endpoint computes
 * the rate from the recorded counts.
 */
class ShadowControllerTest {

    private PolicyRepository policies;
    private ShadowDecisionRepository shadowDecisions;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        policies = Mockito.mock(PolicyRepository.class);
        shadowDecisions = Mockito.mock(ShadowDecisionRepository.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders.standaloneSetup(new ShadowController(policies, shadowDecisions))
                .setControllerAdvice(new ShadowExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void designating_a_shadow_policy_marks_it_and_returns_the_version() throws Exception {
        mockMvc.perform(post("/shadow/policy")
                        .contentType("application/json")
                        .content("{\"version\":\"cand-v2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("cand-v2"));

        verify(policies).markShadow("cand-v2");
    }

    @Test
    void rejects_designating_an_unknown_policy_with_400() throws Exception {
        doThrow(new IllegalArgumentException("no policy to mark shadow with version ghost"))
                .when(policies).markShadow("ghost");

        mockMvc.perform(post("/shadow/policy")
                        .contentType("application/json")
                        .content("{\"version\":\"ghost\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no policy to mark shadow with version ghost"));
    }

    @Test
    void clearing_the_shadow_policy_returns_204() throws Exception {
        mockMvc.perform(delete("/shadow/policy")).andExpect(status().isNoContent());

        verify(policies).clearShadow();
    }

    @Test
    void agreement_reports_counts_and_the_derived_rate() throws Exception {
        when(shadowDecisions.agreementStats("active-v1", "cand-v2"))
                .thenReturn(new AgreementStats("active-v1", "cand-v2", 10, 8, 2, 2, 0));

        mockMvc.perform(get("/shadow/agreement").param("active", "active-v1").param("shadow", "cand-v2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(10))
                .andExpect(jsonPath("$.agree").value(8))
                .andExpect(jsonPath("$.disagree").value(2))
                .andExpect(jsonPath("$.shadowMoreSevere").value(2))
                .andExpect(jsonPath("$.agreementRate").value(0.8));
    }
}
