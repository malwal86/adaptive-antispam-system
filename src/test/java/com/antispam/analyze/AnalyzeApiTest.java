package com.antispam.analyze;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.analyze.web.AnalyzeRequestFixtures;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end of the analyzer slice over HTTP against a real Postgres: a pasted
 * email gets a verdict card payload, a hard-rule hit reports {@code hard_rule} +
 * its reason code (proving 01.04 end-to-end), the decision is durable on refetch,
 * a labeled seed sample analyses by id, and the input contracts return 400/404.
 */
@AutoConfigureMockMvc
class AnalyzeApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ClassificationRepository classifications;

    @Test
    void analyzing_a_denylisted_url_email_blocks_via_hard_rule_and_persists() throws Exception {
        String raw = """
                From: deals@promo.example
                Subject: Act now

                Verify your prize at http://malware.example/login today.
                """;

        MvcResult result = mockMvc.perform(post("/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AnalyzeRequestFixtures.rawJson(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("block"))
                .andExpect(jsonPath("$.routeUsed").value("hard_rule"))
                .andExpect(jsonPath("$.reasonCodes", Matchers.contains("KNOWN_BAD_URL")))
                .andExpect(jsonPath("$.explanation", Matchers.containsString("known-malicious host")))
                .andExpect(jsonPath("$.latencyMs").value(Matchers.greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.emailId").exists())
                .andExpect(jsonPath("$.classificationId").exists())
                .andReturn();

        String emailId = field(result, "emailId");

        // Durable, not just rendered: the decision is in `classifications` and a
        // refetch returns the same verdict.
        org.assertj.core.api.Assertions.assertThat(
                        classifications.findByEmailId(java.util.UUID.fromString(emailId)))
                .singleElement()
                .satisfies(stored -> {
                    org.assertj.core.api.Assertions.assertThat(stored.decision()).isEqualTo(Decision.BLOCK);
                    org.assertj.core.api.Assertions.assertThat(stored.route()).isEqualTo(RouteUsed.HARD_RULE);
                });

        mockMvc.perform(get("/analyze/{emailId}", emailId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("block"))
                .andExpect(jsonPath("$.reasonCodes", Matchers.contains("KNOWN_BAD_URL")));
    }

    @Test
    void analyzing_innocuous_mail_falls_through_to_the_model_path() throws Exception {
        String raw = """
                From: newsletter@good.example
                Subject: Weekly update

                Nothing suspicious here. Read more at https://good.example/news
                """;

        mockMvc.perform(post("/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AnalyzeRequestFixtures.rawJson(raw)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("allow"))
                .andExpect(jsonPath("$.routeUsed").value("model"))
                .andExpect(jsonPath("$.reasonCodes", Matchers.empty()));
    }

    @Test
    void analyzing_an_existing_email_by_id_decides_without_re_pasting() throws Exception {
        IngestResult ingested = ingestService.ingest(
                "From: seed@promo.example\nSubject: prize\n\nClick http://malware.example/win\n"
                        .getBytes(StandardCharsets.UTF_8),
                "seed");

        mockMvc.perform(post("/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AnalyzeRequestFixtures.byIdJson(ingested.emailId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("block"))
                .andExpect(jsonPath("$.routeUsed").value("hard_rule"))
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.emailId").value(ingested.emailId().toString()));
    }

    @Test
    void analyzing_an_empty_paste_returns_400() throws Exception {
        mockMvc.perform(post("/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AnalyzeRequestFixtures.rawJson("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void analyzing_an_unknown_email_id_returns_404() throws Exception {
        mockMvc.perform(post("/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(AnalyzeRequestFixtures.byIdJson(
                                java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void refetching_an_undecided_email_returns_404() throws Exception {
        mockMvc.perform(get("/analyze/{emailId}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    private String field(MvcResult result, String name) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get(name).asText();
    }
}
