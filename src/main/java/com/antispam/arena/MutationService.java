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
 * <p>The engine preserves the seed's ground-truth label by construction: a mutation of spam stays
 * spam, a mutation of legit mail stays ham. Which class a seed may belong to is the caller's
 * {@link Track}: Track A ({@link Track#SPAM}) perturbs abuse seeds to stress recall (story 08.01),
 * Track B ({@link Track#LEGIT}) perturbs legit mail to stress the precision floor (story 08.02b), and
 * each track only applies the strategies that keep its label intact. Iterative attacks that mutate a
 * prior variant are the bounded-loop story (08.02) — all extend the lineage this engine writes.
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
     * Mutates an abuse seed standalone (story 08.01, {@code POST /arena/mutations}): Track A, no run.
     *
     * @param seedEmailId a real abuse seed from the corpus (spam or phish)
     * @param strategy    the perturbation to apply
     * @return the logged variant, with lineage back to the seed
     * @throws MutationException            if the seed is missing, unlabeled, not abuse, or the
     *                                      perturbation was degenerate (blank or unchanged)
     * @throws AttackerUnavailableException if the attacker model cannot be reached
     */
    public AdversarialEmail mutate(UUID seedEmailId, MutationStrategy strategy) {
        return mutateSeed(seedEmailId, strategy, Track.SPAM, null, null);
    }

    /**
     * Mutates a real seed directly under a given {@link Track} (the first generation of an attack run,
     * story 08.02/08.02b), tagging the variant with the run and generation. The track decides which
     * seeds are legal: Track A perturbs abuse seeds (spam/phish) to stress recall, Track B perturbs
     * legit mail (ham) to stress precision — mutating a seed the track cannot preserve is rejected, so
     * a precision run can never be fed an abuse seed nor a recall run a legit one.
     *
     * @param track      the regime this mutation belongs to; gates the seed's eligible label
     * @param runId      the run this variant belongs to, or null for a standalone mutation
     * @param generation the 1-based generation that minted it, or null when standalone
     * @throws MutationException if the seed is missing, unlabeled, or its class is not one this track
     *                           preserves
     */
    public AdversarialEmail mutateSeed(UUID seedEmailId, MutationStrategy strategy, Track track,
            UUID runId, Integer generation) {
        Email seed = emails.findById(seedEmailId)
                .orElseThrow(() -> new MutationException("seed email not found: " + seedEmailId));
        GroundTruthLabel label = labels.findByEmailId(seedEmailId)
                .orElseThrow(() -> new MutationException("seed has no ground-truth label: " + seedEmailId));
        if (!track.accepts(label)) {
            throw new MutationException("seed " + seedEmailId + " is " + label.dbValue()
                    + "; the " + track.token() + " track cannot preserve that ground truth");
        }
        // Mutated directly from the seed, so there is no parent variant (iterative attacks set one).
        return mint(seed, seedEmailId, null, strategy, label, runId, generation);
    }

    /**
     * Mutates an existing variant again — an iterative attack that descends one more generation from
     * {@code parent} (story 08.02). The new variant keeps the family's root {@code seedEmailId} and the
     * preserved ground-truth label, but its immediate parent is {@code parent}: this is how a later
     * generation builds on a variant that bypassed, concentrating the attack on what worked.
     *
     * @param parent     the variant being perturbed further (its content is the mutation's input)
     * @param runId      the run this variant belongs to
     * @param generation the 1-based generation that minted it (the parent's generation + 1)
     */
    public AdversarialEmail mutateVariant(AdversarialEmail parent, MutationStrategy strategy,
            UUID runId, int generation) {
        Email parentEmail = emails.findById(parent.variantEmailId()).orElseThrow(() ->
                new MutationException("parent variant email not found: " + parent.variantEmailId()));
        return mint(parentEmail, parent.seedEmailId(), parent.id(), strategy, parent.label(),
                runId, generation);
    }

    /**
     * The shared minting core: perturb {@code source}'s content under {@code strategy}, reject a
     * degenerate result, ingest the variant off the live spine, and log its lineage. Used by both a
     * seed mutation and an iterative variant mutation, which differ only in what they perturb and what
     * lineage they record.
     */
    private AdversarialEmail mint(Email source, UUID seedEmailId, UUID parentVariantId,
            MutationStrategy strategy, GroundTruthLabel label, UUID runId, Integer generation) {
        String sourceText = new String(source.rawContent(), StandardCharsets.UTF_8);
        String mutated = attacker.mutate(strategy, sourceText);
        if (mutated == null || mutated.isBlank()) {
            throw new MutationException("attacker returned an empty mutation for source " + source.id());
        }
        if (mutated.equals(sourceText)) {
            throw new MutationException(
                    "mutation was a no-op (variant identical to its source) for source " + source.id());
        }

        // Off the live spine: the variant becomes a canonical email so the pipeline can score it,
        // but generating it must not accrue reputation or extract features as a real arrival would.
        IngestResult variant = ingest.ingestOffSpine(mutated.getBytes(StandardCharsets.UTF_8), VARIANT_SOURCE);
        AdversarialEmail logged = variants.save(variant.emailId(), seedEmailId, parentVariantId,
                strategy, label, properties.attackerModel(), runId, generation);
        log.info("minted adversarial variant id={} seed={} parent={} run={} gen={} strategy={} label={} model={}",
                logged.id(), seedEmailId, parentVariantId, runId, generation,
                strategy.dbValue(), label.dbValue(), properties.attackerModel());
        return logged;
    }
}
