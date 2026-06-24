package com.antispam.arena;

import com.antispam.seed.GroundTruthLabel;
import java.time.Instant;
import java.util.UUID;

/**
 * A logged red-team variant (story 08.01): the lineage record over a mutated email. It ties the
 * ingested variant ({@code variantEmailId}, an ordinary {@code emails} row) back to the real seed
 * spam it descends from, records the perturbation applied and the ground-truth class it still
 * belongs to, and names the attacker model that produced it — everything needed to trace an attack
 * and, later, to feed bypassing variants into the retrain corpus.
 *
 * @param id              this lineage record's id
 * @param variantEmailId  the ingested mutated email, scored through the same pipeline as real mail
 * @param seedEmailId     the real seed spam this variant ultimately descends from
 * @param parentVariantId the immediate parent variant (iterative attacks); null when mutated directly
 *                        from the seed
 * @param strategy        the perturbation applied
 * @param label           the ground-truth class, preserved from the seed (spam stays spam)
 * @param attackerModel   the configured attacker model that minted the variant
 * @param runId           the attack run this variant belongs to (story 08.02), or null for a
 *                        standalone mutation (story 08.01)
 * @param generation      the 1-based generation that minted it within the run, or null when standalone
 * @param createdAt       when the variant was logged
 */
public record AdversarialEmail(
        UUID id,
        UUID variantEmailId,
        UUID seedEmailId,
        UUID parentVariantId,
        MutationStrategy strategy,
        GroundTruthLabel label,
        String attackerModel,
        UUID runId,
        Integer generation,
        Instant createdAt) {
}
