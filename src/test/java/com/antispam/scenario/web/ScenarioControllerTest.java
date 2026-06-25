package com.antispam.scenario.web;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.scenario.ScenarioRun;
import com.antispam.scenario.ScenarioService;
import com.antispam.scenario.ThunderclapScript;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the scenario trigger, standalone (no Spring context, no DB) so it runs
 * everywhere. Pins the client-facing outcomes: starting returns 202 with the run acknowledgement, an
 * optional seed is forwarded, an unknown scenario is a 400 (not a 500), and a collision with a
 * running scenario is a 409.
 */
class ScenarioControllerTest {

    private ScenarioService scenarios;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        scenarios = Mockito.mock(ScenarioService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ScenarioController(scenarios))
                .setControllerAdvice(new ScenarioExceptionHandler())
                .build();
    }

    @Test
    void starting_returns_202_with_the_run_acknowledgement() throws Exception {
        when(scenarios.start(ThunderclapScript.NAME, null))
                .thenReturn(new ScenarioRun(ThunderclapScript.NAME, 18, 42L, "thunderclap-shadow-1"));

        mockMvc.perform(post("/controls/scenarios/" + ThunderclapScript.NAME + "/start"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.scenario").value(ThunderclapScript.NAME))
                .andExpect(jsonPath("$.steps").value(18))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.shadowPolicyVersion").value("thunderclap-shadow-1"));
    }

    @Test
    void an_explicit_seed_is_forwarded_to_the_runner() throws Exception {
        when(scenarios.start(ThunderclapScript.NAME, 7L))
                .thenReturn(new ScenarioRun(ThunderclapScript.NAME, 18, 7L, null));

        mockMvc.perform(post("/controls/scenarios/" + ThunderclapScript.NAME + "/start").param("seed", "7"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.seed").value(7))
                // No active policy to derive a shadow from → the field is omitted, not null-valued.
                .andExpect(jsonPath("$.shadowPolicyVersion").doesNotExist());

        verify(scenarios).start(ThunderclapScript.NAME, 7L);
    }

    @Test
    void an_unknown_scenario_is_a_400() throws Exception {
        when(scenarios.start(eq("ghost"), eq(null)))
                .thenThrow(new IllegalArgumentException("unknown scenario: ghost"));

        mockMvc.perform(post("/controls/scenarios/ghost/start"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("unknown scenario: ghost"));
    }

    @Test
    void a_collision_with_a_running_scenario_is_a_409() throws Exception {
        doThrow(new IllegalStateException("a scenario is already running"))
                .when(scenarios).start(ThunderclapScript.NAME, null);

        mockMvc.perform(post("/controls/scenarios/" + ThunderclapScript.NAME + "/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("a scenario is already running"));
    }
}
