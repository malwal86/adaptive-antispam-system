package com.antispam.arena;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The resolved configuration of one bounded attack run (story 08.02): which real seeds to attack,
 * which perturbation strategies to try in the first generation, the attacker's goal bypass rate, and
 * the two bounds that make the loop terminate — the generation cap (3–5) and the hard USD budget.
 *
 * <p>It is the cohesive parameter {@link AttackLoopService#run} consumes, assembled by the caller
 * from the request and the arena defaults ({@link ArenaProperties}). The compact constructor pins the
 * invariants the loop and the schema both rely on, so an out-of-range request is rejected before a
 * run row is written rather than at the database: the seed list and strategy list are non-empty, the
 * target rate is a probability, and the cap is clamped to the PRD's 1–5 bound.
 *
 * @param seedEmailIds     the real abuse seeds the first generation perturbs; must be non-empty
 * @param strategies       the strategies tried per seed in generation one; must be non-empty
 * @param targetBypassRate the attacker's goal bypass rate, in [0,1]
 * @param generationCap    the hard cap on generations, clamped to 1–5
 * @param budgetUsd        the hard spend ceiling for the run; must be positive
 */
public record AttackRunConfig(
        List<UUID> seedEmailIds,
        List<MutationStrategy> strategies,
        double targetBypassRate,
        int generationCap,
        BigDecimal budgetUsd) {

    /** The PRD's upper bound on generations (§Subsystem 6: "3–5 generations"). */
    public static final int MAX_GENERATIONS = 5;

    public AttackRunConfig {
        if (seedEmailIds == null || seedEmailIds.isEmpty()) {
            throw new MutationException("an attack run needs at least one seed to perturb");
        }
        if (strategies == null || strategies.isEmpty()) {
            throw new MutationException("an attack run needs at least one mutation strategy");
        }
        if (targetBypassRate < 0 || targetBypassRate > 1) {
            throw new MutationException("target bypass rate must be in [0,1]: " + targetBypassRate);
        }
        if (budgetUsd == null || budgetUsd.signum() <= 0) {
            throw new MutationException("an attack run needs a positive budget: " + budgetUsd);
        }
        seedEmailIds = List.copyOf(seedEmailIds);
        strategies = List.copyOf(strategies);
        // Clamp rather than reject: the loop must be bounded, and 1–5 is the PRD's range.
        generationCap = Math.max(1, Math.min(generationCap, MAX_GENERATIONS));
    }
}
