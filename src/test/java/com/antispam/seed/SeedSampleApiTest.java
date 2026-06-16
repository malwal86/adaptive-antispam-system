package com.antispam.seed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The sample picker returns a balanced, labeled slice of the seed corpus: with
 * several spam but only one ham/phish, {@code perLabel=1} yields exactly one of
 * each class — the picker always offers a ham/spam/phish to try.
 */
@AutoConfigureMockMvc
class SeedSampleApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    private void seed(String raw, GroundTruthLabel label, String dataset) {
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, dataset);
    }

    @Test
    void returns_a_balanced_labeled_slice_with_subject_and_domain() throws Exception {
        seed("From: a@promo.example\nSubject: prize one\n\nbody\n", GroundTruthLabel.SPAM, "spamassassin");
        seed("From: b@promo.example\nSubject: prize two\n\nbody\n", GroundTruthLabel.SPAM, "spamassassin");
        seed("From: c@good.example\nSubject: hello\n\nbody\n", GroundTruthLabel.HAM, "enron");
        seed("From: d@phish.example\nSubject: verify\n\nbody\n", GroundTruthLabel.PHISH, "phishtank");

        mockMvc.perform(get("/seed/samples").param("perLabel", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(3)))
                .andExpect(jsonPath("$[*].label",
                        Matchers.containsInAnyOrder("ham", "spam", "phish")))
                .andExpect(jsonPath("$[0].emailId").exists())
                .andExpect(jsonPath("$[*].subject",
                        Matchers.hasItem(Matchers.containsString("hello"))))
                .andExpect(jsonPath("$[*].senderDomain",
                        Matchers.hasItem("good.example")));
    }
}
