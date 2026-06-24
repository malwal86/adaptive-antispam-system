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
 * with both below the cap and its partial results still recorded (AC 5). The two-track run (story
 * 08.02b) reports recall and precision pressure separately: {@link #actualBypassRate} is the Track A
 * bypass rate (abuse reaching the inbox) and {@link #precisionFpRate} is the Track B false-positive
 * rate (legit mail wrongly blocked). Each is null until the run terminates, and stays null if that
 * track did not run.
 *
 * @param id                    this run's id; the parent of every variant it mints
 * @param attackerModel         the configured red-team model ({@code antispam.arena.attacker-model})
 * @param defenderModel         the model artifact the defender's active policy is calibrated for
 * @param defenderPolicyVersion the policy captured at start and held fixed for the whole run
 * @param targetBypassRate      the attacker's goal bypass rate in [0,1]
 * @param actualBypassRate      the Track A recall bypass rate in [0,1] — abuse variants that reached
 *                              the inbox; null until the run terminates, and null after if no Track A
 *                              ran (a legit-only run)
 * @param precisionFpRate       the Track B precision false-positive rate in [0,1] — legit variants the
 *                              defender wrongly blocked (story 08.02b); null until terminated, and null
 *                              after if no Track B ran (a spam-only run)
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
        Double precisionFpRate,
        int generationCap,
        BigDecimal budgetUsd,
        BigDecimal spentUsd,
        int generationsRun,
        RunStatus status,
        Instant createdAt,
        Instant completedAt) {
}
