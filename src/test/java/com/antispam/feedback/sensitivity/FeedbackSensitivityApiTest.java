package com.antispam.feedback.sensitivity;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The {@code POST /feedback/sensitivity} endpoint (story 07.04): runs a sweep and returns the curve
 * plus the breakdown summary. Uses a tiny explicit sweep so the slice is exercised end-to-end
 * without ingesting the default-sized corpus; the curve's correctness is pinned by
 * {@link FeedbackSensitivityIntegrationTest}.
 */
@AutoConfigureMockMvc
class FeedbackSensitivityApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void sweep_runs_and_returns_a_blunted_curve_with_a_documented_breakdown() throws Exception {
        String body = """
                {"seed": 5, "populationSize": 8, "maliciousFractions": [0.0, 0.5], "streamPerSender": 4}
                """;

        mockMvc.perform(post("/feedback/sensitivity").contentType("application/json").content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.points[0].maliciousFraction").value(0.0))
                .andExpect(jsonPath("$.points[0].blunted").value(true))
                .andExpect(jsonPath("$.points[1].blunted").value(true))
                .andExpect(jsonPath("$.breakdownBomberCount").value(15));
    }
}
