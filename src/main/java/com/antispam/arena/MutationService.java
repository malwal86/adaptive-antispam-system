package com.antispam.arena;

import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The seed-grounded mutation engine (story 08.01): it perturbs a <em>real</em> seed spam from the
 * corpus rather than inventing an attack, so every variant is realistic and traceable to its seed
 * (PRD §Subsystem 6, §Risks). One mutation is: load the seed and its ground-truth label, ask the
 * configured attacker model to apply one {@link MutationStrategy}, ingest the result as a canonical
 * email <em>off the live spine</em> (so the variant is scoreable by the same pipeline as real mail
 * without perturbing live reputation/features), and log an {@link AdversarialEmail} whose lineage
 * points back to the seed.
 *
 * <p>The engine mutates only abuse seeds: a mutation of spam stays spam by construction, so the
 * variant's ground-truth label is the seed's and recall is what gets stressed. Mutating legit mail
 * to defend the precision floor is the two-track story (08.03); iterative attacks that mutate a
 * prior variant are the bounded-loop story (08.02) — both extend the lineage this engine writes.
 */
@Service
public class MutationService {

    private static final String VARIANT_SOURCE = "adversarial";

    private static final Logger log = LoggerFactory.getLogger(MutationService.class);

    private final EmailRepository emails;
    private final GroundTruthLabelRepository labels;
    private final AttackerPort attacker;
    private final IngestService ingest;
    private final AdversarialEmailRepository variants;
    private final ArenaProperties properties;

    @Autowired
    public MutationService(EmailRepository emails, GroundTruthLabelRepository labels,
            AttackerPort attacker, IngestService ingest, AdversarialEmailRepository variants,
            ArenaProperties properties) {
        this.emails = emails;
        this.labels = labels;
        this.attacker = attacker;
        this.ingest = ingest;
        this.variants = variants;
        this.properties = properties;
    }

    /**
     * Mutates the seed email under {@code strategy} and logs the resulting variant.
     *
     * @param seedEmailId a real abuse seed from the corpus (spam or phish)
     * @param strategy    the perturbation to apply
     * @return the logged variant, with lineage back to the seed
     * @throws MutationException            if the seed is missing, unlabeled, not abuse, or the
     *                                      perturbation was degenerate (blank or unchanged)
     * @throws AttackerUnavailableException if the attacker model cannot be reached
     */
    public AdversarialEmail mutate(UUID seedEmailId, MutationStrategy strategy) {
        Email seed = emails.findById(seedEmailId)
                .orElseThrow(() -> new MutationException("seed email not found: " + seedEmailId));
        GroundTruthLabel label = labels.findByEmailId(seedEmailId)
                .orElseThrow(() -> new MutationException("seed has no ground-truth label: " + seedEmailId));
        if (label == GroundTruthLabel.HAM) {
            throw new MutationException(
                    "seed " + seedEmailId + " is ham; the mutation engine perturbs abuse seeds only");
        }

        String seedText = new String(seed.rawContent(), StandardCharsets.UTF_8);
        String mutated = attacker.mutate(strategy, seedText);
        if (mutated == null || mutated.isBlank()) {
            throw new MutationException("attacker returned an empty mutation for seed " + seedEmailId);
        }
        if (mutated.equals(seedText)) {
            throw new MutationException(
                    "mutation was a no-op (variant identical to seed) for seed " + seedEmailId);
        }

        // Off the live spine: the variant becomes a canonical email so the pipeline can score it,
        // but generating it must not accrue reputation or extract features as a real arrival would.
        IngestResult variant = ingest.ingestOffSpine(mutated.getBytes(StandardCharsets.UTF_8), VARIANT_SOURCE);
        // Mutated directly from the seed, so there is no parent variant (iterative attacks set one).
        AdversarialEmail logged = variants.save(
                variant.emailId(), seedEmailId, null, strategy, label, properties.attackerModel());
        log.info("minted adversarial variant id={} seed={} strategy={} label={} model={}",
                logged.id(), seedEmailId, strategy.dbValue(), label.dbValue(), properties.attackerModel());
        return logged;
    }
}
