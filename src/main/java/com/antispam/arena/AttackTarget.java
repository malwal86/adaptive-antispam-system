package com.antispam.arena;

import java.util.UUID;

/**
 * One unit of attacker work within a generation (story 08.02): perturb a source under a chosen
 * strategy, on a given {@link Track}. The source is either the run's real seed directly (generation
 * one) or a prior variant ({@code parentVariant} non-null) when a later generation builds on something
 * that beat the defender — which is how the loop concentrates on previously-successful
 * strategies/variants (AC 2). The track travels with the target so a Track A (recall) and a Track B
 * (precision) attack never cross: a seed's mutation and every descendant stay in one track.
 *
 * @param seedEmailId   the family's root seed, always carried so a variant's lineage is complete
 * @param parentVariant the variant to mutate further, or null to mutate the seed directly (generation one)
 * @param strategy      the perturbation to apply
 * @param track         the regime this work belongs to (abuse → recall, legit → precision)
 */
record AttackTarget(UUID seedEmailId, AdversarialEmail parentVariant, MutationStrategy strategy,
        Track track) {

    /** A first-generation target: mutate the seed itself, with no parent variant yet. */
    static AttackTarget ofSeed(UUID seedEmailId, MutationStrategy strategy, Track track) {
        return new AttackTarget(seedEmailId, null, strategy, track);
    }
}
