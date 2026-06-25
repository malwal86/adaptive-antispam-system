package com.antispam.eval.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.eval.EvalIntegrityReport;
import com.antispam.eval.EvalIntegrityService;
import com.antispam.eval.JudgingSourceAudit;
import com.antispam.eval.SplitAudit;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the eval-integrity endpoint, standalone (no Spring context, no DB). It pins the
 * shape a demo or the promotion pipeline reads: the time-forward leakage numbers and the judging-source
 * numbers, with {@code holds} true exactly when both halves are sound.
 */
class EvalIntegrityControllerTest {

    private EvalIntegrityService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(EvalIntegrityService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvalIntegrityController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void reports_the_time_forward_and_judging_integrity_numbers() throws Exception {
        SplitAudit timeForward = new SplitAudit(
                100, 80, 20, 30, 0, 0, Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
        JudgingSourceAudit judging = new JudgingSourceAudit(20, 0, 0, 7);
        when(service.report()).thenReturn(new EvalIntegrityReport(timeForward, judging));

        mockMvc.perform(get("/eval/integrity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds").value(true))
                .andExpect(jsonPath("$.crossBoundaryGroups").value(0))
                .andExpect(jsonPath("$.temporalInversions").value(0))
                .andExpect(jsonPath("$.leakageFree").value(true))
                .andExpect(jsonPath("$.judgingTotal").value(20))
                .andExpect(jsonPath("$.feedbackDerivedInJudging").value(0))
                .andExpect(jsonPath("$.feedbackLabeledTotal").value(7))
                .andExpect(jsonPath("$.noSelfJudge").value(true));
    }
}
