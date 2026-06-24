package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the bounded attack loop end-to-end against the real database (story 08.02): a run over real
 * seed spam is recorded in {@code adversarial_runs}, every variant it mints is logged in
 * {@code adversarial_emails} tagged with the run and its 1-based generation, the loop terminates
 * within its bounds (never unbounded, AC 3), the defender stays the policy captured at start (AC 4),
 * and no live classification is minted for a variant (isolation, story 09.03). The attacker is a
 * deterministic stub bean, so the slice runs keyless. Skips without Docker.
 */
class AttackLoopIntegrationTest extends AbstractPostgresIntegrationTest {

    /** A deterministic attacker so the loop needs no provider: it perturbs by tagging per generation. */
    @TestConfiguration
    static class StubAttacker {
        @Bean
        @Primary
        AttackerPort stubAttackerPort() {
            // Vary the output by strategy AND a random token so each generation's perturbation differs
            // from its source (never a no-op) and from its siblings.
            return (strategy, seedContent) ->
                    "X-Mutation: " + strategy.dbValue() + " " + UUID.randomUUID() + "\n" + seedContent;
        }
    }

    @Autowired
    private AttackLoopService loop;

    @Autowired
    private AdversarialRunRepository runs;

    @Autowired
    private AdversarialEmailRepository variants;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void runs_a_bounded_budgeted_loop_recording_the_run_and_its_per_generation_variants() {
        Policy defender = policies.findActive().orElseThrow();
        UUID seedA = seedSpam("loopA");
        UUID seedB = seedSpam("loopB");

        AdversarialRun run = loop.run(new AttackRunConfig(
                List.of(seedA, seedB), List.of(),
                List.of(MutationStrategy.SYNONYM, MutationStrategy.HOMOGLYPH),
                0.5, 3, new BigDecimal("1.00")));

        // The run is recorded and finalized (AC 1): a terminal status, the captured fixed defender, and
        // a bounded generation count it could not exceed (AC 3, AC 4).
        assertThat(run.status()).isIn(RunStatus.COMPLETED, RunStatus.BUDGET_EXHAUSTED);
        assertThat(run.defenderPolicyVersion()).isEqualTo(defender.version());
        assertThat(run.defenderModel()).isEqualTo(defender.modelVersion());
        assertThat(run.generationsRun()).isBetween(1, run.generationCap());
        assertThat(run.generationCap()).isEqualTo(3);
        assertThat(run.actualBypassRate()).isBetween(0.0, 1.0);
        assertThat(run.completedAt()).isNotNull();
        assertThat(runs.findById(run.id())).contains(run);

        // Every variant is tagged with this run and a generation within the cap; generation one exists
        // (the loop always runs at least the seed generation) and descends from a real seed.
        List<AdversarialEmail> minted = variants.findByRun(run.id());
        assertThat(minted).isNotEmpty();
        assertThat(minted).allSatisfy(v -> {
            assertThat(v.runId()).isEqualTo(run.id());
            assertThat(v.generation()).isBetween(1, run.generationCap());
            assertThat(v.seedEmailId()).isIn(seedA, seedB);
            assertThat(v.label()).isEqualTo(GroundTruthLabel.SPAM);
            // Track A only (no legit seeds), and every scored variant carries the defender's verdict.
            assertThat(v.track()).isEqualTo(Track.SPAM);
            assertThat(v.defenderDelivered()).isNotNull();
        });
        assertThat(minted).anySatisfy(v -> assertThat(v.generation()).isEqualTo(1));
        // A first-generation variant has no parent; any later generation builds on a prior variant.
        assertThat(minted).filteredOn(v -> v.generation() == 1)
                .allSatisfy(v -> assertThat(v.parentVariantId()).isNull());
        assertThat(minted).filteredOn(v -> v.generation() > 1)
                .allSatisfy(v -> assertThat(v.parentVariantId()).isNotNull());

        // Isolation (story 09.03): scoring the variants minted no enforced classification for them.
        assertThat(classificationCountFor(minted.stream().map(AdversarialEmail::variantEmailId).toList()))
                .isZero();
    }

    private UUID seedSpam(String tag) {
        byte[] raw = ("From: spammer@" + tag + ".evil.test\nSubject: free money\n\n"
                + "Claim your prize at http://" + tag + ".evil.test now\n").getBytes(StandardCharsets.UTF_8);
        IngestResult ingested = ingestService.ingestOffSpine(raw, "seed");
        labels.saveIfAbsent(ingested.emailId(), GroundTruthLabel.SPAM, "test-corpus");
        return ingested.emailId();
    }

    private long classificationCountFor(List<UUID> ids) {
        return jdbc.query(connection -> {
            var ps = connection.prepareStatement(
                    "select count(*) from classifications where email_id = any(?)");
            ps.setArray(1, connection.createArrayOf("uuid", ids.toArray()));
            return ps;
        }, rs -> {
            rs.next();
            return rs.getLong(1);
        });
    }
}
