package com.antispam.decision;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A persisted decision about an email: which {@link Decision} was reached, the
 * {@link ReasonCode}s that justify it, the {@link RouteUsed route} that produced
 * it, and how long that route took. One row per decision in the
 * {@code classifications} table (every model/policy decision is recorded there).
 *
 * <p>Story 01.04 writes hard-rule and model rows; story 04.01 adds the model's
 * {@code spam_score}/{@code phishing_score}/{@code model_version} via {@code scores};
 * story 04.04 adds the reputation-fused {@code posterior} and uncertainty band via
 * {@code fused}. The data model's {@code policy_version} and {@code llm_cost_usd}
 * columns arrive with the epics that produce them (04.05, 05) rather than being
 * speculatively modeled here.
 *
 * @param id          canonical identifier of this classification
 * @param emailId     the {@code emails} row this decision is about
 * @param decision    the verdict tier
 * @param reasonCodes the codes justifying it (may be empty)
 * @param route       the pipeline stage that produced it
 * @param latencyMs   milliseconds that route spent deciding
 * @param scores      the model's raw scores, or {@code null} for a hard-rule row
 * @param fused       the reputation-fused posterior, or {@code null} on a hard-rule row
 *                    or a model row scored before any calibration was installed
 * @param createdAt   when the decision was recorded
 */
public record Classification(
        UUID id,
        UUID emailId,
        Decision decision,
        List<ReasonCode> reasonCodes,
        RouteUsed route,
        long latencyMs,
        ModelScores scores,
        FusedScore fused,
        Instant createdAt) {

    public Classification {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
