package com.antispam.eval.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.eval.EvalSetService;
import com.antispam.eval.GoldenSetVersion;
import com.antispam.seed.GroundTruthLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the eval-set endpoints, standalone (no Spring context, no DB). It pins the
 * client-facing shapes a report reads: freezing returns the version's provenance and class balance, and
 * re-freezing a frozen label maps to 409 (the immutability rule surfaced as a conflict, not a 500).
 */
class EvalSetControllerTest {

    private EvalSetService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(EvalSetService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new EvalSetController(service))
                .setControllerAdvice(new EvalSetExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void freezing_returns_the_version_provenance_and_class_balance() throws Exception {
        when(service.freezeGolden("golden-1"))
                .thenReturn(new GoldenSetVersion("golden-1", 0.2, 42L, 3, Instant.EPOCH));
        when(service.goldenCountsByLabel("golden-1")).thenReturn(Map.of(
                GroundTruthLabel.SPAM, 2L, GroundTruthLabel.HAM, 1L));

        mockMvc.perform(post("/eval/golden/{version}", "golden-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("golden-1"))
                .andExpect(jsonPath("$.evalFraction").value(0.2))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.spam").value(2))
                .andExpect(jsonPath("$.ham").value(1));
    }

    @Test
    void re_freezing_a_frozen_version_maps_to_409() throws Exception {
        when(service.freezeGolden(any()))
                .thenThrow(new IllegalArgumentException("golden version 'golden-1' is already frozen"));

        mockMvc.perform(post("/eval/golden/{version}", "golden-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void appending_a_fresh_attack_returns_the_fresh_set_balance() throws Exception {
        when(service.freshCountsByLabel()).thenReturn(Map.of(GroundTruthLabel.PHISH, 1L));

        mockMvc.perform(post("/eval/fresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"emailId":"11111111-1111-1111-1111-111111111111",
                                 "label":"PHISH","source":"reported"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.phish").value(1));
    }
}
