package com.antispam.arena;

import java.util.UUID;

/**
 * One unit of attacker work within a generation (story 08.02): perturb a source under a chosen
 * strategy. The source is either the run's real seed directly (generation one) or a prior variant
 * ({@code parentVariant} non-null) when a later generation builds on something that bypassed — which
 * is how the loop concentrates on previously-successful strategies (AC 2).
 *
 * @param seedEmailId   the family's root seed, always carried so a variant's lineage is complete
 * @param parentVariant the variant to mutate further, or null to mutate the seed directly (generation one)
 * @param strategy      the perturbation to apply
 */
record AttackTarget(UUID seedEmailId, AdversarialEmail parentVariant, MutationStrategy strategy) {

    /** A first-generation target: mutate the seed itself, with no parent variant yet. */
    static AttackTarget ofSeed(UUID seedEmailId, MutationStrategy strategy) {
        return new AttackTarget(seedEmailId, null, strategy);
    }
}
