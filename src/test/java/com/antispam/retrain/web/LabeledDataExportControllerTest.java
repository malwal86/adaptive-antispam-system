package com.antispam.retrain.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.retrain.LabeledDataExportService;
import com.antispam.retrain.TrainingExample;
import com.antispam.retrain.TrainingExport;
import com.antispam.seed.GroundTruthLabel;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Web-contract test for the export endpoint, standalone (no Spring context, no DB). It pins the
 * client-facing shape: {@code GET /retrain/export} returns 200 with the feature version, per-source
 * counts, and examples whose provenance is embedded JSON (an object, not an escaped string) — the form
 * the CI train step (10.02) consumes.
 */
class LabeledDataExportControllerTest {

    private LabeledDataExportService service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = Mockito.mock(LabeledDataExportService.class);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LabeledDataExportController(service, mapper))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
    }

    @Test
    void returns_the_export_with_per_source_counts_and_embedded_provenance() throws Exception {
        UUID seedEmail = UUID.randomUUID();
        UUID feedbackEmail = UUID.randomUUID();
        when(service.export()).thenReturn(new TrainingExport(1, List.of(
                new TrainingExample(seedEmail, GroundTruthLabel.SPAM, 1.0, "seed",
                        "{\"datasetSource\":\"spamassassin\"}", 1),
                new TrainingExample(feedbackEmail, GroundTruthLabel.HAM, 0.7, "feedback",
                        "{\"corroborators\":3}", 1))));

        mockMvc.perform(get("/retrain/export"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.featureVersion").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.countsBySource.seed").value(1))
                .andExpect(jsonPath("$.countsBySource.feedback").value(1))
                .andExpect(jsonPath("$.examples[0].emailId").value(seedEmail.toString()))
                .andExpect(jsonPath("$.examples[0].label").value("spam"))
                .andExpect(jsonPath("$.examples[0].weight").value(1.0))
                // Provenance is embedded JSON — a navigable object, not a quoted string.
                .andExpect(jsonPath("$.examples[0].provenance.datasetSource").value("spamassassin"))
                .andExpect(jsonPath("$.examples[1].weight").value(0.7))
                .andExpect(jsonPath("$.examples[1].provenance.corroborators").value(3));
    }
}
