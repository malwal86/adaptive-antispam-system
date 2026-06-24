package com.antispam.arena;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One red-team campaign (story 08.02): the bounded, budgeted iterative attack loop as a durable
 * record (PRD §Subsystem 6, §Data Model {@code adversarial_runs}). It names the two regimes pitted
 * against each other — the configured {@code attackerModel} and the {@code defenderModel} the active
 * policy is calibrated for — and pins the defender for the whole run via {@link #defenderPolicyVersion},
 * the policy captured once at start and used to score every generation (AC 4: the defender adapts only
 * <em>between</em> runs via retrain, never within one).
 *
 * <p>It also records both bounds that make the loop terminate by construction (AC 3) — the
 * {@link #generationCap} (3–5) and the hard {@link #budgetUsd} spend ceiling — alongside how much of
 * each was consumed ({@link #generationsRun}, {@link #spentUsd}). A budget-exhausted run stops early
 * with both below the cap and its partial results still recorded (AC 5). {@link #actualBypassRate} is
 * null until the run terminates; on termination it is the share of variants that slipped past the
 * fixed defender across the whole run.
 *
 * @param id                    this run's id; the parent of every variant it mints
 * @param attackerModel         the configured red-team model ({@code antispam.arena.attacker-model})
 * @param defenderModel         the model artifact the defender's active policy is calibrated for
 * @param defenderPolicyVersion the policy captured at start and held fixed for the whole run
 * @param targetBypassRate      the attacker's goal bypass rate in [0,1]
 * @param actualBypassRate      the achieved bypass rate in [0,1]; null until the run terminates
 * @param generationCap         the hard cap on generations (3–5)
 * @param budgetUsd             the hard ceiling on attacker spend for the run
 * @param spentUsd              attacker spend consumed so far
 * @param generationsRun        how many generations actually ran (≤ cap)
 * @param status                the run's lifecycle state
 * @param createdAt             when the run started
 * @param completedAt           when the run terminated; null while running
 */
public record AdversarialRun(
        UUID id,
        String attackerModel,
        String defenderModel,
        String defenderPolicyVersion,
        double targetBypassRate,
        Double actualBypassRate,
        int generationCap,
        BigDecimal budgetUsd,
        BigDecimal spentUsd,
        int generationsRun,
        RunStatus status,
        Instant createdAt,
        Instant completedAt) {
}
