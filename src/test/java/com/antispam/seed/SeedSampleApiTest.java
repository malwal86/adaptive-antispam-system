package com.antispam.seed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The sample picker returns labeled samples joined to their email metadata.
 *
 * <p>The integration suite shares one Postgres container, and other classes (e.g.
 * the corpus loader) also write {@code ground_truth_labels}, so this test does not
 * assume it is the only seeder: it seeds its own uniquely-identifiable emails and
 * asserts they appear with the right label/subject/domain, plus that all three
 * classes are represented — rather than asserting on global ordering or counts.
 */
@AutoConfigureMockMvc
class SeedSampleApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    private UUID seed(String raw, GroundTruthLabel label, String dataset) {
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, dataset);
        return result.emailId();
    }

    @Test
    void returns_labeled_samples_with_subject_and_domain_for_each_class() throws Exception {
        UUID hamId = seed(
                "From: c@seedpick-ham.example\nSubject: seedpick ham hello\n\nbody\n",
                GroundTruthLabel.HAM, "enron");
        seed("From: a@seedpick-spam.example\nSubject: seedpick spam one\n\nbody\n",
                GroundTruthLabel.SPAM, "spamassassin");
        seed("From: b@seedpick-spam.example\nSubject: seedpick spam two\n\nbody\n",
                GroundTruthLabel.SPAM, "spamassassin");
        seed("From: d@seedpick-phish.example\nSubject: seedpick phish verify\n\nbody\n",
                GroundTruthLabel.PHISH, "phishtank");

        // Ask for a wide slice so freshly-seeded (newest) rows are included
        // regardless of any corpus data another test class may have loaded.
        mockMvc.perform(get("/seed/samples").param("perLabel", "25"))
                .andExpect(status().isOk())
                // All three ground-truth classes are represented.
                .andExpect(jsonPath("$[*].label", Matchers.hasItems("ham", "spam", "phish")))
                // Our freshly-seeded ham appears, carrying its metadata (no address PII).
                .andExpect(jsonPath("$[?(@.emailId=='" + hamId + "')].label",
                        Matchers.contains("ham")))
                .andExpect(jsonPath("$[?(@.emailId=='" + hamId + "')].subject",
                        Matchers.contains("seedpick ham hello")))
                .andExpect(jsonPath("$[?(@.emailId=='" + hamId + "')].senderDomain",
                        Matchers.contains("seedpick-ham.example")))
                .andExpect(jsonPath("$[?(@.emailId=='" + hamId + "')].dataset",
                        Matchers.contains("enron")));
    }

    @Test
    void clamps_per_label_to_at_least_one() throws Exception {
        seed("From: e@seedpick-clamp.example\nSubject: seedpick clamp\n\nbody\n",
                GroundTruthLabel.HAM, "enron");

        // perLabel=0 is clamped up to 1, so the picker still returns samples.
        mockMvc.perform(get("/seed/samples").param("perLabel", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.not(Matchers.empty())));
    }
}
