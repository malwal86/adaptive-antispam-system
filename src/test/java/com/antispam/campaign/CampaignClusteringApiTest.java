package com.antispam.campaign;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.embedding.EmailEmbeddingService;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The campaign-clustering endpoints (story 06.03): {@code POST /campaigns/cluster} runs
 * the offline job and reports the run; {@code GET /campaigns/clusters/email/{id}} returns
 * an email's campaign or 404 when it has none. The test seeds and embeds its own emails
 * (the suite shares the corpus), then asserts on the configured threshold and the
 * per-email membership rather than global counts.
 */
@AutoConfigureMockMvc
class CampaignClusteringApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EmailEmbeddingService embeddingService;

    private UUID ingestAndEmbed(String body) {
        String tag = UUID.randomUUID().toString();
        String raw = "Subject: api shipping [" + tag + "]\n\n" + body + " ref:" + tag;
        UUID emailId = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "campaign-api").emailId();
        embeddingService.embedAndStore(emailId).orElseThrow();
        return emailId;
    }

    @Test
    void cluster_runs_the_job_and_reports_the_run() throws Exception {
        ingestAndEmbed("your order 1234 has shipped and is on its way to Austin today");
        ingestAndEmbed("your order 5678 has shipped and is on its way to Berlin today");

        mockMvc.perform(post("/campaigns/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.modelVersion").isNotEmpty())
                .andExpect(jsonPath("$.emailCount", Matchers.greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.clusterCount", Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.similarityThreshold").value(0.8));
    }

    @Test
    void membership_is_returned_for_a_clustered_email() throws Exception {
        UUID emailId = ingestAndEmbed("your package 9012 has shipped and is on its way to Denver this morning");
        mockMvc.perform(post("/campaigns/cluster")).andExpect(status().isOk());

        mockMvc.perform(get("/campaigns/clusters/email/{emailId}", emailId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailId").value(emailId.toString()))
                .andExpect(jsonPath("$.clusterId").isNotEmpty())
                .andExpect(jsonPath("$.modelVersion").isNotEmpty())
                .andExpect(jsonPath("$.clusterSize", Matchers.greaterThanOrEqualTo(1)));
    }

    @Test
    void membership_is_404_for_an_unclustered_email() throws Exception {
        // A random id was never ingested, so no clustering run could have placed it.
        mockMvc.perform(get("/campaigns/clusters/email/{emailId}", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }
}
