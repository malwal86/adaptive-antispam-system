package com.antispam.arena;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The resolved configuration of one bounded attack run (story 08.02 / 08.02b): which real seeds to
 * attack on each track, which perturbation strategies to try in the first generation, the attacker's
 * goal bypass rate, and the two bounds that make the loop terminate — the generation cap (3–5) and the
 * hard USD budget.
 *
 * <p>The run is <em>two-track</em> and its track mix is configurable (AC 5): {@link #spamSeedEmailIds}
 * are the abuse seeds Track A perturbs to stress recall, and {@link #legitSeedEmailIds} are the legit
 * (ham) seeds Track B perturbs to stress the precision floor. A run may enable either track or both;
 * it needs at least one seed somewhere. The configured strategies are intersected per track with the
 * ones that track can apply, so Track B only ever paraphrases/reformats legit mail (never reframes or
 * homoglyphs it, which would not leave it ham).
 *
 * <p>It is the cohesive parameter {@link AttackLoopService#run} consumes, assembled by the caller from
 * the request and the arena defaults ({@link ArenaProperties}). The compact constructor pins the
 * invariants the loop and the schema both rely on, so an out-of-range request is rejected before a run
 * row is written: at least one seed exists, the strategy list is non-empty, the target rate is a
 * probability, and the cap is clamped to the PRD's 1–5 bound.
 *
 * @param spamSeedEmailIds  the abuse seeds Track A perturbs (recall stress); may be empty if Track B runs
 * @param legitSeedEmailIds the legit (ham) seeds Track B perturbs (precision stress); may be empty if Track A runs
 * @param strategies        the strategies tried per seed in generation one; must be non-empty
 * @param targetBypassRate  the attacker's goal bypass rate, in [0,1]
 * @param generationCap     the hard cap on generations, clamped to 1–5
 * @param budgetUsd         the hard spend ceiling for the run; must be positive
 */
public record AttackRunConfig(
        List<UUID> spamSeedEmailIds,
        List<UUID> legitSeedEmailIds,
        List<MutationStrategy> strategies,
        double targetBypassRate,
        int generationCap,
        BigDecimal budgetUsd) {

    /** The PRD's upper bound on generations (§Subsystem 6: "3–5 generations"). */
    public static final int MAX_GENERATIONS = 5;

    public AttackRunConfig {
        spamSeedEmailIds = spamSeedEmailIds == null ? List.of() : List.copyOf(spamSeedEmailIds);
        legitSeedEmailIds = legitSeedEmailIds == null ? List.of() : List.copyOf(legitSeedEmailIds);
        if (spamSeedEmailIds.isEmpty() && legitSeedEmailIds.isEmpty()) {
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
        strategies = List.copyOf(strategies);
        // Clamp rather than reject: the loop must be bounded, and 1–5 is the PRD's range.
        generationCap = Math.max(1, Math.min(generationCap, MAX_GENERATIONS));
    }
}
