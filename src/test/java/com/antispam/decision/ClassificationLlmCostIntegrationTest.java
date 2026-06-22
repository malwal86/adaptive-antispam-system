package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Round-trips the {@code llm_cost_usd} column through a real Postgres (story 05.02 AC 5): an
 * LLM-routed decision records its cost on the {@code classifications} row, and a decision that
 * never called the LLM stores NULL — the null-vs-zero distinction the migration relies on. This
 * pins the persistence wiring (INSERT, the {@code numeric(12,6)} binding, and the read-back mapper)
 * end to end, not just in memory.
 */
class ClassificationLlmCostIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ClassificationRepository classifications;

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    @Test
    void records_the_llm_cost_on_an_llm_routed_decision() {
        Email email = ingest("""
                From: sender@uncertain.example
                Subject: ambiguous message [llm-cost-it-1]

                Could you confirm the details we discussed?
                """);
        ModelScores scores = new ModelScores(0.4, 0.2, "bootstrap-v1", 0.55);
        DecisionOutcome routed = new DecisionOutcome(Decision.QUARANTINE, List.of(), RouteUsed.LLM, 250L, scores);
        BigDecimal cost = new BigDecimal("0.001234");

        classifications.save(email.id(), routed, null, "bootstrap-v1", cost);

        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.route()).isEqualTo(RouteUsed.LLM);
                    assertThat(stored.llmCostUsd()).isEqualByComparingTo("0.001234");
                });
    }

    @Test
    void stores_null_cost_when_the_decision_did_not_call_the_llm() {
        Email email = ingest("""
                From: sender@plain.example
                Subject: routine message [llm-cost-it-2]

                Thanks, talk soon.
                """);
        DecisionOutcome hardRule = new DecisionOutcome(
                Decision.BLOCK, List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 3L);

        classifications.save(email.id(), hardRule, null, "bootstrap-v1", null);

        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.route()).isEqualTo(RouteUsed.HARD_RULE);
                    assertThat(stored.llmCostUsd()).isNull();
                });
    }
}
