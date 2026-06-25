package com.antispam.controls.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The left-rail control surface over HTTP against a real Postgres (story 12.02): policies list,
 * switching and threshold changes actually move the active {@code policies} row the decision path
 * reads, and the budget caps round-trip. The active policy is a shared global row, so the original is
 * restored after each test (see the shared-DB-state convention).
 */
@AutoConfigureMockMvc
class ControlsApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PolicyRepository policies;

    private String originalActiveVersion;

    @BeforeEach
    void captureActive() {
        originalActiveVersion = policies.findActive().orElseThrow().version();
    }

    @AfterEach
    void restoreActive() {
        policies.activate(originalActiveVersion);
    }

    @Test
    void lists_policies_including_the_active_one() throws Exception {
        mockMvc.perform(get("/controls/policies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.version == '" + originalActiveVersion + "')]").exists())
                .andExpect(jsonPath("$[?(@.active == true)]").exists());
    }

    @Test
    void applying_thresholds_mints_and_activates_a_new_policy_the_decision_path_reads() throws Exception {
        var request = new ThresholdsRequest(0.10, 0.40, 0.70, 0.55, 0.08);

        mockMvc.perform(post("/controls/thresholds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.warnThreshold").value(0.10))
                .andExpect(jsonPath("$.quarantineThreshold").value(0.40))
                .andExpect(jsonPath("$.blockThreshold").value(0.70));

        // The active policy the pipeline reads now carries the new cut-points, and the active
        // policy's burst threshold/model were carried over from the previous regime.
        Policy active = policies.findActive().orElseThrow();
        assertThat(active.warnThreshold()).isEqualTo(0.10);
        assertThat(active.blockThreshold()).isEqualTo(0.70);
        assertThat(active.llmThreshold()).isEqualTo(0.55);
        assertThat(active.version()).startsWith("console-");
    }

    @Test
    void rejects_a_non_monotonic_threshold_ladder_with_400() throws Exception {
        var bad = new ThresholdsRequest(0.80, 0.30, 0.90, 0.55, 0.08);
        mockMvc.perform(post("/controls/thresholds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void activating_a_known_version_succeeds_and_an_unknown_one_is_400() throws Exception {
        mockMvc.perform(post("/controls/policies/" + originalActiveVersion + "/activate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(originalActiveVersion))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(post("/controls/policies/does-not-exist/activate"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void budget_caps_round_trip() throws Exception {
        var request = new BudgetCapsRequest(0.20, 3.50);

        mockMvc.perform(post("/controls/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCapUsd").value(0.20))
                .andExpect(jsonPath("$.monthlyCapUsd").value(3.50));

        mockMvc.perform(get("/controls/budget"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dailyCapUsd").value(0.20))
                .andExpect(jsonPath("$.monthlyCapUsd").value(3.50));
    }

    @Test
    void rejects_a_daily_cap_above_the_monthly_cap_with_400() throws Exception {
        var bad = new BudgetCapsRequest(9.00, 1.00);
        mockMvc.perform(post("/controls/budget")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
    }
}
