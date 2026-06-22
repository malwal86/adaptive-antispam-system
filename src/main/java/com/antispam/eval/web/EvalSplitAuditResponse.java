package com.antispam.eval.web;

import com.antispam.eval.EvalSplitProperties;
import com.antispam.eval.SplitAudit;
import java.time.Instant;

/**
 * {@code POST /eval/split} result: the leakage-free evidence for the split just
 * rebuilt, plus the configuration that produced it. {@code crossBoundaryGroups} and
 * {@code temporalInversions} are the numbers a reviewer points at to trust the eval
 * — {@code leakageFree} is true exactly when both are zero.
 *
 * @param total                emails assigned (train + eval)
 * @param train                emails on the train side
 * @param eval                 emails held out for eval
 * @param groups               distinct families after the singleton rule
 * @param crossBoundaryGroups  families straddling the boundary (must be 0)
 * @param temporalInversions   eval emails older than the newest train email
 * @param leakageFree          whether the split is leakage-free
 * @param latestTrainTime      newest train email, or null if train is empty
 * @param earliestEvalTime     oldest eval email, or null if eval is empty
 * @param evalFraction         the held-out share requested
 * @param seed                 the tie-break seed used
 */
public record EvalSplitAuditResponse(
        int total,
        int train,
        int eval,
        int groups,
        int crossBoundaryGroups,
        int temporalInversions,
        boolean leakageFree,
        Instant latestTrainTime,
        Instant earliestEvalTime,
        double evalFraction,
        long seed) {

    public static EvalSplitAuditResponse from(SplitAudit audit, EvalSplitProperties config) {
        return new EvalSplitAuditResponse(
                audit.total(),
                audit.trainCount(),
                audit.evalCount(),
                audit.groupCount(),
                audit.crossBoundaryGroups(),
                audit.temporalInversions(),
                audit.leakageFree(),
                audit.latestTrainTime(),
                audit.earliestEvalTime(),
                config.evalFraction(),
                config.seed());
    }
}
