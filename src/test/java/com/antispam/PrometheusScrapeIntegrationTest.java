package com.antispam;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.DecisionService;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * Pins the observable contract of story 13.01: after real decisions are made, Prometheus can scrape
 * the actuator {@code /prometheus} endpoint and find the decision pipeline's meters — fast-path
 * latency (as a percentile histogram), the {@code route_used} mix, decisions by tier, and the LLM
 * budget caps — with sensible names and labels. The endpoint is remapped to the application root
 * (see application.yml), so the scrape target is {@code /prometheus}, alongside {@code /health} and
 * {@code /info}.
 *
 * <p>A hard-rule BLOCK is driven deterministically (a known-bad URL short-circuits the model and the
 * LLM), so {@code route="HARD_RULE"} and {@code tier="BLOCK"} are guaranteed present without standing
 * up the model or a provider.
 */
@AutoConfigureMockMvc
class PrometheusScrapeIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private IngestService ingestService;

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    @Test
    void scrape_exposes_the_decision_pipeline_meters_after_a_decision_is_made() throws Exception {
        Email email = ingest("""
                From: deals@promo.example
                Subject: Act now

                Verify your prize at http://malware.example/login today.
                """);
        decisionService.decide(email);

        MvcResult result = mockMvc.perform(MockMvcRequestBuilders.get("/prometheus"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn();
        String scrape = result.getResponse().getContentAsString();

        // route_used mix (AC 2) — the final route of the decision, here the hard-rule short-circuit.
        assertThat(scrape).contains("antispam_decision_route_total");
        assertThat(scrape).contains("route=\"HARD_RULE\"");

        // decisions by tier (AC 4).
        assertThat(scrape).contains("antispam_decision_tier_total");
        assertThat(scrape).contains("tier=\"BLOCK\"");

        // fast-path latency with a percentile histogram so p95/p99 are aggregable (AC 1): a
        // histogram-backed timer emits *_bucket series Prometheus can run histogram_quantile over.
        assertThat(scrape).contains("antispam_decision_latency_seconds");
        assertThat(scrape).contains("antispam_decision_latency_seconds_bucket");

        // LLM budget caps so cost-vs-cap / budget-remaining is drawable (AC 3); the gauge registers
        // at startup, independent of whether any LLM call was made.
        assertThat(scrape).contains("antispam_llm_budget_cap_usd");
        assertThat(scrape).contains("scope=\"daily\"");

        // Every series is attributable to this service.
        assertThat(scrape).contains("application=\"living-antispam\"");
    }
}
