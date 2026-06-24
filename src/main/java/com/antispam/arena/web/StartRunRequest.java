package com.antispam.arena.web;

import com.antispam.arena.ArenaProperties;
import com.antispam.arena.AttackRunConfig;
import com.antispam.arena.MutationStrategy;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The request to start a bounded, two-track attack run (story 08.02 / 08.02b). The track mix is the
 * caller's choice (AC 5): {@link #spamSeedEmailIds} enable Track A (mutate abuse, stress recall) and
 * {@link #legitSeedEmailIds} enable Track B (mutate legit mail, stress the precision floor); a run
 * supplies at least one seed on either track. The strategy set and the two bounds (generation cap,
 * budget) fall back to the arena defaults ({@link ArenaProperties}) when omitted, so the common case is
 * a short request and the cost ceiling is in force whether or not the caller names one.
 *
 * @param spamSeedEmailIds  the abuse seeds to perturb on Track A; may be empty if Track B is used
 * @param legitSeedEmailIds the legit (ham) seeds to perturb on Track B; may be empty if Track A is used
 * @param targetBypassRate  the attacker's goal bypass rate in [0,1]; required
 * @param strategies        the strategies tried per seed in generation one; defaults to all four
 * @param generationCap     the hard generation cap (1–5); defaults to the configured cap
 * @param budgetUsd         the hard spend ceiling; defaults to the configured budget
 */
public record StartRunRequest(
        List<UUID> spamSeedEmailIds,
        List<UUID> legitSeedEmailIds,
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
                spamSeedEmailIds,
                legitSeedEmailIds,
                chosen,
                targetBypassRate,
                generationCap == null ? defaults.generationCap() : generationCap,
                budgetUsd == null ? defaults.budgetUsd() : budgetUsd);
    }
}
