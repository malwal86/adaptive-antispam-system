package com.antispam.arena.web;

import com.antispam.arena.ArenaProperties;
import com.antispam.arena.AttackRunConfig;
import com.antispam.arena.MutationStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The request to start a bounded attack run (story 08.02). Only the seeds to attack and the target
 * bypass rate are required; the strategy set and the two bounds (generation cap, budget) fall back to
 * the arena defaults ({@link ArenaProperties}) when omitted, so the common case is a short request and
 * the cost ceiling is in force whether or not the caller names one.
 *
 * @param seedEmailIds     the real abuse seeds to perturb; required, non-empty
 * @param targetBypassRate the attacker's goal bypass rate in [0,1]; required
 * @param strategies       the strategies tried per seed in generation one; defaults to all four
 * @param generationCap    the hard generation cap (1–5); defaults to the configured cap
 * @param budgetUsd        the hard spend ceiling; defaults to the configured budget
 */
public record StartRunRequest(
        List<UUID> seedEmailIds,
        double targetBypassRate,
        List<MutationStrategy> strategies,
        Integer generationCap,
        BigDecimal budgetUsd) {

    /** Resolves the request into a run config, filling any omitted field from the arena defaults. */
    public AttackRunConfig toConfig(ArenaProperties defaults) {
        List<MutationStrategy> chosen = strategies == null || strategies.isEmpty()
                ? List.of(MutationStrategy.values())
                : strategies;
        return new AttackRunConfig(
                seedEmailIds,
                chosen,
                targetBypassRate,
                generationCap == null ? defaults.generationCap() : generationCap,
                budgetUsd == null ? defaults.budgetUsd() : budgetUsd);
    }
}
