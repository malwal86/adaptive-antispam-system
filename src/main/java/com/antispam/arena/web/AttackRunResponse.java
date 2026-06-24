package com.antispam.arena.web;

import com.antispam.arena.AdversarialRun;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The wire view of an {@link AdversarialRun} (story 08.02): the run's config, the defender it was
 * pinned to, how it terminated, and the result. {@code status} is rendered as its stable lowercase
 * token, matching how it is stored. The achieved bypass rate is null until the run terminates.
 *
 * @param id                    the run's id; the parent of every variant it minted
 * @param attackerModel         the configured red-team model
 * @param defenderModel         the model the defender's active policy is calibrated for
 * @param defenderPolicyVersion the policy held fixed for the whole run
 * @param targetBypassRate      the attacker's goal bypass rate
 * @param actualBypassRate      the Track A recall bypass rate, or null while running / if no Track A ran
 * @param precisionFpRate       the Track B precision false-positive rate (story 08.02b), or null while
 *                              running / if no Track B ran
 * @param generationCap         the hard generation cap (1–5)
 * @param generationsRun        how many generations actually ran
 * @param budgetUsd             the hard spend ceiling
 * @param spentUsd              attacker spend consumed
 * @param status                the terminal (or current) lifecycle state (token)
 * @param createdAt             when the run started
 * @param completedAt           when it terminated, or null while running
 */
public record AttackRunResponse(
        UUID id,
        String attackerModel,
        String defenderModel,
        String defenderPolicyVersion,
        double targetBypassRate,
        Double actualBypassRate,
        Double precisionFpRate,
        int generationCap,
        int generationsRun,
        BigDecimal budgetUsd,
        BigDecimal spentUsd,
        String status,
        Instant createdAt,
        Instant completedAt) {

    public static AttackRunResponse from(AdversarialRun run) {
        return new AttackRunResponse(
                run.id(),
                run.attackerModel(),
                run.defenderModel(),
                run.defenderPolicyVersion(),
                run.targetBypassRate(),
                run.actualBypassRate(),
                run.precisionFpRate(),
                run.generationCap(),
                run.generationsRun(),
                run.budgetUsd(),
                run.spentUsd(),
                run.status().dbValue(),
                run.createdAt(),
                run.completedAt());
    }
}
