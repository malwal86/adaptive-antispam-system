package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves the two-track persistence against the real database (story 08.02b): the fixed defender's
 * verdict is stamped per variant, and the wrongly-blocked legit variants are captured — still labeled
 * ham, with run provenance — as the precision-floor retrain corpus (AC 4, Epic 10/11). The capture
 * query returns only the ham variants the defender withheld: delivered ham (no false positive) and
 * blocked abuse (a correct catch, not good mail) are both excluded.
 */
class AdversarialEmailRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AdversarialEmailRepository variants;

    @Autowired
    private AdversarialRunRepository runs;

    @Autowired
    private IngestService ingestService;

    @Test
    void captures_only_the_legit_variants_the_defender_wrongly_blocked() {
        AdversarialRun run = runs.start("attacker-x", "model-7", "pol-active",
                0.5, 3, new BigDecimal("1.00"));
        UUID seed = ingest("seed for the family");

        AdversarialEmail blockedHam = save(run.id(), GroundTruthLabel.HAM, MutationStrategy.STRUCTURE, seed);
        AdversarialEmail deliveredHam = save(run.id(), GroundTruthLabel.HAM, MutationStrategy.SYNONYM, seed);
        AdversarialEmail blockedSpam = save(run.id(), GroundTruthLabel.SPAM, MutationStrategy.SYNONYM, seed);

        // Stamp each variant's defender verdict, the way the loop does after scoring.
        variants.recordDefenderOutcome(blockedHam.id(), false);   // good mail withheld → a false positive
        variants.recordDefenderOutcome(deliveredHam.id(), true);  // good mail delivered → correct
        variants.recordDefenderOutcome(blockedSpam.id(), false);  // abuse withheld → a correct catch

        // The verdict reads back on the variant.
        assertThat(variants.findById(blockedHam.id())).get()
                .extracting(AdversarialEmail::defenderDelivered).isEqualTo(false);

        // Only the wrongly-blocked legit variant is captured for the precision-floor corpus.
        assertThat(variants.findWronglyBlockedHam(run.id()))
                .extracting(AdversarialEmail::id)
                .containsExactly(blockedHam.id());
    }

    private AdversarialEmail save(UUID runId, GroundTruthLabel label, MutationStrategy strategy, UUID seed) {
        UUID variantEmail = ingest("variant " + UUID.randomUUID());
        return variants.save(variantEmail, seed, null, strategy, label, "attacker-x", runId, 1);
    }

    private UUID ingest(String body) {
        IngestResult ingested = ingestService.ingestOffSpine(
                ("Subject: t\n\n" + body + "\n").getBytes(StandardCharsets.UTF_8), "adversarial");
        return ingested.emailId();
    }
}
