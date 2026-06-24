package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.retrain.RetrainLabel;
import com.antispam.retrain.RetrainLabelRepository;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Proves bypass measurement and corpus feedback end-to-end against the real database (story 08.04). A
 * completed run is, by the time it returns, also measured against the fixed baseline (AC 2) and its
 * bypassing variants are fed into the retrain corpus labeled with arena provenance (AC 3) — the
 * defender beaten on either track (abuse delivered, legit wrongly blocked) becomes labeled training
 * signal, and arena ground truth is tagged a distinct source so Epic 11 can keep it out of the golden
 * judging set (AC 5). The cross-run trend reports the run's place in the bypass-rate series (AC 4). The
 * attacker is a deterministic stub bean, so the slice runs keyless. Skips without Docker.
 */
class BypassMeasurementIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class StubAttacker {
        @Bean
        @Primary
        AttackerPort stubAttackerPort() {
            return (strategy, seedContent) ->
                    "X-Mutation: " + strategy.dbValue() + " " + UUID.randomUUID() + "\n" + seedContent;
        }
    }

    @Autowired
    private AttackLoopService loop;
    @Autowired
    private BypassMeasurementService measurement;
    @Autowired
    private AdversarialEmailRepository variants;
    @Autowired
    private RetrainLabelRepository retrainLabels;
    @Autowired
    private PolicyRepository policies;
    @Autowired
    private IngestService ingestService;
    @Autowired
    private GroundTruthLabelRepository labels;

    @Test
    void a_completed_run_records_the_baseline_and_feeds_bypassing_variants_to_the_corpus() {
        Policy genesis = policies.findOldest().orElseThrow();
        UUID spamSeed = seed("measureS", "spammer@evil.test",
                "Subject: free money\n\nClaim your prize at http://evil.test now\n", GroundTruthLabel.SPAM);
        UUID legitSeed = seed("measureH", "colleague@work.example",
                "Subject: lunch\n\nWant to grab lunch at noon? Let me know.\n", GroundTruthLabel.HAM);

        AdversarialRun run = loop.run(new AttackRunConfig(
                List.of(spamSeed), List.of(legitSeed),
                List.of(MutationStrategy.SYNONYM, MutationStrategy.STRUCTURE),
                0.5, 2, new BigDecimal("1.00")));

        // AC 2: the run's variants were also scored against the fixed baseline — the genesis policy by
        // default — and that comparison is stamped on the run beside the current defender's rate.
        assertThat(run.baselinePolicyVersion()).isEqualTo(genesis.version());
        assertThat(run.baselineBypassRate()).isNotNull().isBetween(0.0, 1.0); // Track A ran

        // AC 3 + story 08.02b AC 4: every variant that beat the fixed defender — abuse it delivered, legit
        // it wrongly blocked — is in the corpus exactly once, labeled with its preserved ground truth and
        // tagged source=arena; every variant the defender handled correctly contributes no label.
        List<AdversarialEmail> minted = variants.findByRun(run.id());
        for (AdversarialEmail variant : minted) {
            List<RetrainLabel> arenaLabels = retrainLabels.findByEmailId(variant.variantEmailId()).stream()
                    .filter(label -> label.source().equals("arena"))
                    .toList();
            if (beatDefender(variant)) {
                assertThat(arenaLabels).hasSize(1);
                RetrainLabel label = arenaLabels.get(0);
                assertThat(label.label()).isEqualTo(variant.label()); // ground truth preserved
                assertThat(label.weight()).isGreaterThan(0.0);
                // Provenance is JSONB: Postgres reserializes it (spacing, key order), so parse and
                // assert the value rather than substring-matching the raw text.
                JsonNode provenance = readJson(label.provenance());
                assertThat(provenance.get("runId").asText()).isEqualTo(run.id().toString());
                assertThat(provenance.get("variantId").asText()).isEqualTo(variant.id().toString());
                assertThat(provenance.get("outcome").asText())
                        .isEqualTo(variant.track() == Track.SPAM ? "bypass" : "false_positive");
            } else {
                assertThat(arenaLabels).isEmpty();
            }
        }
        // The two finder queries that drive the feed agree with the per-variant verdicts.
        assertThat(variants.findBypassingAbuse(run.id()))
                .allSatisfy(v -> assertThat(v.label()).isIn(GroundTruthLabel.SPAM, GroundTruthLabel.PHISH));
        assertThat(variants.findWronglyBlockedHam(run.id()))
                .allSatisfy(v -> assertThat(v.label()).isEqualTo(GroundTruthLabel.HAM));

        // AC 4: the run appears on the cross-run bypass-rate trend.
        BypassTrend trend = measurement.trend(100);
        assertThat(trend.points()).anySatisfy(p -> assertThat(p.runId()).isEqualTo(run.id()));
    }

    private static boolean beatDefender(AdversarialEmail variant) {
        boolean delivered = Boolean.TRUE.equals(variant.defenderDelivered());
        return variant.track() == Track.SPAM ? delivered : !delivered;
    }

    private static JsonNode readJson(String json) {
        try {
            return new ObjectMapper().readTree(json);
        } catch (JsonProcessingException e) {
            throw new AssertionError("provenance was not valid JSON: " + json, e);
        }
    }

    private UUID seed(String tag, String from, String body, GroundTruthLabel label) {
        byte[] raw = ("From: " + from + "\nX-Seed: " + tag + "\n" + body).getBytes(StandardCharsets.UTF_8);
        IngestResult ingested = ingestService.ingestOffSpine(raw, "seed");
        labels.saveIfAbsent(ingested.emailId(), label, "test-corpus");
        return ingested.emailId();
    }
}
