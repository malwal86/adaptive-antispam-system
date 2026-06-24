package com.antispam.arena.web;

import com.antispam.arena.AdversarialEmail;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire view of a logged variant (story 08.01): the lineage record returned when a mutation is
 * minted or read back. Strategy and label are rendered as their stable lowercase tokens, matching
 * how they are stored, so the console and tests read the same vocabulary the database does.
 *
 * @param id              the lineage record's id
 * @param variantEmailId  the ingested mutated email, scoreable through the pipeline
 * @param seedEmailId     the real seed spam this variant descends from
 * @param parentVariantId the parent variant for an iterative attack, or null
 * @param strategy        the perturbation applied (token)
 * @param groundTruthLabel the preserved ground-truth class (token)
 * @param attackerModel   the attacker model that minted the variant
 * @param runId           the attack run this variant belongs to (story 08.02), or null when standalone
 * @param generation      the 1-based generation that minted it within the run, or null when standalone
 * @param createdAt       when the variant was logged
 */
public record AdversarialEmailResponse(
        UUID id,
        UUID variantEmailId,
        UUID seedEmailId,
        UUID parentVariantId,
        String strategy,
        String groundTruthLabel,
        String attackerModel,
        UUID runId,
        Integer generation,
        Instant createdAt) {

    public static AdversarialEmailResponse from(AdversarialEmail variant) {
        return new AdversarialEmailResponse(
                variant.id(),
                variant.variantEmailId(),
                variant.seedEmailId(),
                variant.parentVariantId(),
                variant.strategy().dbValue(),
                variant.label().dbValue(),
                variant.attackerModel(),
                variant.runId(),
                variant.generation(),
                variant.createdAt());
    }
}
