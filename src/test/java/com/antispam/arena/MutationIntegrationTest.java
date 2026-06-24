package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.ExperimentContext;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Proves the mutation slice end-to-end against the real database (story 08.01): a real seed spam is
 * perturbed by a (stubbed) attacker, the variant is ingested as a canonical email and logged in
 * {@code adversarial_emails} with strategy / preserved label / seed lineage, and the variant is then
 * scored through the same {@link PolicyScorer} as real mail under a read-only experiment scope —
 * shadow/replay-safe, minting no live classification (AC 5). The Spring AI provider is never touched:
 * the attacker is a deterministic stub bean, so the slice runs keyless. Skips without Docker.
 */
class MutationIntegrationTest extends AbstractPostgresIntegrationTest {

    /** A deterministic attacker so the slice needs no provider: it tags the seed text per strategy. */
    @TestConfiguration
    static class StubAttacker {
        @Bean
        @Primary
        AttackerPort stubAttackerPort() {
            return (strategy, seedContent) -> "X-Mutation: " + strategy.dbValue() + "\n" + seedContent;
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private MutationService mutationService;

    @Autowired
    private AdversarialEmailRepository variants;

    @Autowired
    private EmailRepository emails;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private PolicyScorer scorer;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void mutates_a_real_seed_into_a_logged_variant_with_strategy_label_and_lineage() {
        UUID seedId = seedSpam("lineage");

        AdversarialEmail variant = mutationService.mutate(seedId, MutationStrategy.SYNONYM);

        // Lineage: a real variant descended from the seed, mutated directly (no parent), label preserved.
        assertThat(variant.seedEmailId()).isEqualTo(seedId);
        assertThat(variant.parentVariantId()).isNull();
        assertThat(variant.strategy()).isEqualTo(MutationStrategy.SYNONYM);
        assertThat(variant.label()).isEqualTo(GroundTruthLabel.SPAM);
        assertThat(variant.variantEmailId()).isNotEqualTo(seedId);

        // The lineage row is readable back as part of the seed's attack family.
        assertThat(variants.findBySeed(seedId)).extracting(AdversarialEmail::id).contains(variant.id());

        // The variant content is a distinct canonical email, ingested off the live spine as 'adversarial'.
        Email variantEmail = emails.findById(variant.variantEmailId()).orElseThrow();
        assertThat(new String(variantEmail.rawContent(), StandardCharsets.UTF_8)).contains("X-Mutation: synonym");
        assertThat(variantEmail.ingestSource()).isEqualTo("adversarial");
    }

    @Test
    void the_variant_is_scoreable_through_the_same_pipeline_without_minting_a_live_classification() {
        UUID seedId = seedSpam("scoreable");
        AdversarialEmail variant = mutationService.mutate(seedId, MutationStrategy.HOMOGLYPH);
        Email variantEmail = emails.findById(variant.variantEmailId()).orElseThrow();
        Policy active = policies.findActive().orElseThrow();

        // AC 5: scored by the very same scorer real mail uses, under a read-only experiment scope so a
        // stray live-state write would be blocked — exactly how shadow (09.02) and replay (09.01) score.
        ScoredDecision scored = ExperimentContext.callReadOnly(() -> scorer.score(variantEmail, active));

        assertThat(scored).isNotNull();
        assertThat(scored.decision()).isNotNull();
        assertThat(scored.policyVersion()).isEqualTo(active.version());
        // No enforced classification was minted for the variant (the arena never enforces).
        assertThat(classificationCountFor(Set.of(variant.variantEmailId()))).isZero();
    }

    /** Ingests a real spam and labels it, returning its canonical id; the seed the engine perturbs. */
    private UUID seedSpam(String tag) {
        byte[] raw = ("From: spammer@" + tag + ".evil.test\nSubject: free money\n\n"
                + "Claim your prize at http://" + tag + ".evil.test now\n").getBytes(StandardCharsets.UTF_8);
        IngestResult ingested = ingestService.ingestOffSpine(raw, "seed");
        labels.saveIfAbsent(ingested.emailId(), GroundTruthLabel.SPAM, "test-corpus");
        return ingested.emailId();
    }

    private long classificationCountFor(Set<UUID> ids) {
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
