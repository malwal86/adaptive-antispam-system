package com.antispam.arena.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.arena.AdversarialEmailRepository;
import com.antispam.arena.AdversarialRun;
import com.antispam.arena.AdversarialRunRepository;
import com.antispam.arena.ArenaProperties;
import com.antispam.arena.AttackLoopService;
import com.antispam.arena.BypassMeasurementService;
import com.antispam.arena.BypassTrend;
import com.antispam.arena.BypassTrendPoint;
import com.antispam.arena.MutationException;
import com.antispam.arena.RunStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the bounded-loop endpoints, standalone (no Spring context, no provider, no DB).
 * It pins the client-facing outcomes: starting a run returns 201 with the run summary (config, fixed
 * defender, terminal status, achieved bypass rate, and the baseline comparison), an invalid run config
 * is a 400 not a 500, and the trend endpoint reports the cross-run bypass-rate series (story 08.04).
 */
class AttackRunControllerTest {

    private AttackLoopService loop;
    private AdversarialRunRepository runs;
    private BypassMeasurementService measurement;
    private MockMvc mockMvc;

    private static final UUID RUN_ID = UUID.randomUUID();
    private static final UUID SEED_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        loop = Mockito.mock(AttackLoopService.class);
        runs = Mockito.mock(AdversarialRunRepository.class);
        measurement = Mockito.mock(BypassMeasurementService.class);
        AdversarialEmailRepository variants = Mockito.mock(AdversarialEmailRepository.class);
        ArenaProperties props = new ArenaProperties(true, "attacker-x", 3, null, null, null, 1.0);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new AttackRunController(loop, runs, variants, measurement, props))
                .setControllerAdvice(new ArenaExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void starting_a_run_returns_201_with_the_run_summary() throws Exception {
        when(loop.run(any())).thenReturn(new AdversarialRun(RUN_ID, "attacker-x", "model-7", "pol-active",
                0.4, 0.5, 0.25, "pol-genesis", 0.8, 3, new BigDecimal("1.00"), new BigDecimal("0.06"), 3,
                RunStatus.COMPLETED, Instant.EPOCH, Instant.EPOCH));

        mockMvc.perform(post("/arena/runs").contentType("application/json").content("""
                        {"spamSeedEmailIds":["%s"],"targetBypassRate":0.4}
                        """.formatted(SEED_ID)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.defenderPolicyVersion").value("pol-active"))
                .andExpect(jsonPath("$.actualBypassRate").value(0.5))
                .andExpect(jsonPath("$.precisionFpRate").value(0.25))
                // The "danger missed by baseline" comparison rides on the run summary (story 08.04).
                .andExpect(jsonPath("$.baselinePolicyVersion").value("pol-genesis"))
                .andExpect(jsonPath("$.baselineBypassRate").value(0.8))
                .andExpect(jsonPath("$.generationsRun").value(3))
                .andExpect(jsonPath("$.status").value("completed"));
    }

    @Test
    void the_trend_endpoint_reports_the_cross_run_bypass_rate_series() throws Exception {
        UUID earlier = UUID.randomUUID();
        UUID later = UUID.randomUUID();
        when(measurement.trend(eq(20))).thenReturn(new BypassTrend(List.of(
                new BypassTrendPoint(earlier, Instant.EPOCH, 0.6, 0.8, 0.1),
                new BypassTrendPoint(later, Instant.EPOCH.plusSeconds(60), 0.3, 0.8, 0.1)),
                0.6, 0.3, true));

        mockMvc.perform(get("/arena/trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2))
                .andExpect(jsonPath("$.points[0].runId").value(earlier.toString()))
                .andExpect(jsonPath("$.points[0].bypassRate").value(0.6))
                .andExpect(jsonPath("$.points[1].bypassRate").value(0.3))
                .andExpect(jsonPath("$.firstBypassRate").value(0.6))
                .andExpect(jsonPath("$.latestBypassRate").value(0.3))
                .andExpect(jsonPath("$.improved").value(true));
    }

    @Test
    void an_invalid_run_config_is_a_400() throws Exception {
        when(loop.run(any())).thenThrow(new MutationException("an attack run needs at least one seed"));

        mockMvc.perform(post("/arena/runs").contentType("application/json").content("""
                        {"spamSeedEmailIds":["%s"],"targetBypassRate":0.4}
                        """.formatted(SEED_ID)))
                .andExpect(status().isBadRequest());
    }
}
