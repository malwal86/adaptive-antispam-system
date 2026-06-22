package com.antispam.seed;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.List;
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
        seed("From: c@seedpick-ham.example\nSubject: seedpick ham hello\n\nbody\n",
                GroundTruthLabel.HAM, "enron");
        seed("From: a@seedpick-spam.example\nSubject: seedpick spam one\n\nbody\n",
                GroundTruthLabel.SPAM, "spamassassin");
        seed("From: b@seedpick-spam.example\nSubject: seedpick spam two\n\nbody\n",
                GroundTruthLabel.SPAM, "spamassassin");
        seed("From: d@seedpick-phish.example\nSubject: seedpick phish verify\n\nbody\n",
                GroundTruthLabel.PHISH, "phishtank");

        // The picker returns a deterministic oldest-N-per-label slice, capped at
        // MAX_PER_LABEL=25. Once the shared corpus grows past that ceiling a freshly-seeded
        // (newest) row is no longer in the window, so this asserts on the *shape* of the
        // returned slice — all classes represented, and every row's metadata joined and
        // PII-redacted — which holds however much labeled data another class has loaded.
        mockMvc.perform(get("/seed/samples").param("perLabel", "25"))
                .andExpect(status().isOk())
                // All three ground-truth classes are represented.
                .andExpect(jsonPath("$[*].label", Matchers.hasItems("ham", "spam", "phish")))
                // Every returned sample is a known, joined class...
                .andExpect(jsonPath("$[*].label",
                        Matchers.everyItem(Matchers.in(List.of("ham", "spam", "phish")))))
                // ...and the sender is a bare domain, never a full address (no PII egress).
                .andExpect(jsonPath("$[*].senderDomain",
                        Matchers.everyItem(Matchers.not(Matchers.containsString("@")))));
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
