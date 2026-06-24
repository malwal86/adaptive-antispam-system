package com.antispam.retrain.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.retrain.GateEvidence;
import com.antispam.retrain.GateResult;
import com.antispam.retrain.PrecisionGateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the gate endpoint, standalone (no Spring context, no DB). It pins the
 * client-facing shape the retrain pipeline (10.04) consumes: {@code GET /retrain/gate?run=} returns
 * 200 with the pass/fail verdict, the precision-vs-floor it turned on, and the reported evidence; and
 * a not-yet-gradeable run maps to 409 (poll) rather than a 500.
 */
class PrecisionGateControllerTest {

    private PrecisionGateService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(PrecisionGateService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new PrecisionGateController(service))
                .setControllerAdvice(new PrecisionGateExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void returns_the_gate_verdict_with_precision_floor_and_evidence() throws Exception {
        UUID run = UUID.randomUUID();
        when(service.evaluate(run)).thenReturn(new GateResult(
                false, 0.80, 0.95, 120, "candidate-v2", "model-7",
                new GateEvidence(0.92, 0.08, 0.10, 30.0, 50),
                "precision 0.8000 < floor 0.9500 on 120 golden emails"));

        mockMvc.perform(get("/retrain/gate").param("run", run.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.passed").value(false))
                .andExpect(jsonPath("$.precision").value(0.80))
                .andExpect(jsonPath("$.precisionFloor").value(0.95))
                .andExpect(jsonPath("$.goldenSampleCount").value(120))
                .andExpect(jsonPath("$.policyVersion").value("candidate-v2"))
                .andExpect(jsonPath("$.modelVersion").value("model-7"))
                // The reported evidence travels alongside the verdict, non-blocking.
                .andExpect(jsonPath("$.evidence.recall").value(0.92))
                .andExpect(jsonPath("$.evidence.bypassRate").value(0.08))
                .andExpect(jsonPath("$.evidence.abuseTotal").value(50));
    }

    @Test
    void maps_a_not_yet_gradeable_run_to_409() throws Exception {
        when(service.evaluate(any())).thenThrow(
                new IllegalStateException("no golden-set decisions for replay run x"));

        mockMvc.perform(get("/retrain/gate").param("run", UUID.randomUUID().toString()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }
}
