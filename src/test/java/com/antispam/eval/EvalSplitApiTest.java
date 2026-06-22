package com.antispam.eval;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The split endpoints: {@code POST /eval/split} rebuilds and returns the leakage-free
 * audit, {@code GET /eval/split} reports the materialized split's class balance. The
 * test seeds its own labeled emails (the suite shares the corpus) and asserts on the
 * always-true grouping invariant and the configured knobs rather than exact counts.
 */
@AutoConfigureMockMvc
class EvalSplitApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    private void seed(String domain, int year, GroundTruthLabel label) {
        String raw = "From: sender@" + domain + ".evalsplitapi.test\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: api " + domain + " " + year + "\n\nbody " + java.util.UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, "t-evalsplitapi-" + domain);
    }

    @Test
    void rebuild_returns_a_leakage_free_audit_with_the_configured_knobs() throws Exception {
        seed("a", 2001, GroundTruthLabel.HAM);
        seed("b", 2098, GroundTruthLabel.SPAM);
        seed("c", 2099, GroundTruthLabel.PHISH);

        mockMvc.perform(post("/eval/split"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crossBoundaryGroups").value(0))
                .andExpect(jsonPath("$.total", Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.evalFraction").value(0.2))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.train", Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.eval", Matchers.greaterThan(0)));
    }

    @Test
    void current_returns_the_materialized_split_balance() throws Exception {
        seed("d", 2001, GroundTruthLabel.HAM);
        seed("e", 2099, GroundTruthLabel.PHISH);
        mockMvc.perform(post("/eval/split")).andExpect(status().isOk());

        mockMvc.perform(get("/eval/split"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.evalFraction").value(0.2))
                .andExpect(jsonPath("$.seed").value(42))
                .andExpect(jsonPath("$.sides", Matchers.not(Matchers.empty())));
    }
}
