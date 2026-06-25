package com.antispam.eval.web;

import com.antispam.eval.EvalIntegrityReport;
import java.time.Instant;

/**
 * {@code GET /eval/integrity} result: the eval-integrity numbers a reviewer or demo cites (story
 * 11.03). The time-forward block backs "detects evolving spam" — the holdout sits in the corpus's
 * future ({@code earliestEvalTime} beyond {@code latestTrainTime}) and no family straddles the split
 * ({@code crossBoundaryGroups == 0}). The judging block backs "the eval isn't grading itself" —
 * {@code feedbackDerivedInJudging == 0} and {@code judgingWithoutGroundTruth == 0}. {@code holds} is
 * true exactly when every guarantee holds at once.
 *
 * @param holds                     whether every eval-integrity guarantee holds
 * @param total                     emails in the materialized split
 * @param train                     emails on the train side
 * @param eval                      emails on the eval side
 * @param groups                    distinct families in the split
 * @param crossBoundaryGroups       families straddling the split (must be 0)
 * @param temporalInversions        eval emails older than the newest train email (time-forward number)
 * @param leakageFree               whether the split is grouped + time-forward with 0 leakage
 * @param latestTrainTime           newest train email, or null if train is empty
 * @param earliestEvalTime          oldest eval email, or null if eval is empty
 * @param judgingTotal              distinct judging-set emails (eval ∪ golden ∪ fresh)
 * @param judgingWithoutGroundTruth judging emails lacking a high-confidence label (must be 0)
 * @param feedbackDerivedInJudging  judging emails graded only by feedback (must be 0 — anti-circular)
 * @param feedbackLabeledTotal      emails carrying a feedback label at all (context: they train only)
 * @param noSelfJudge               whether no judging email is graded by feedback alone
 */
public record EvalIntegrityResponse(
        boolean holds,
        int total,
        int train,
        int eval,
        int groups,
        int crossBoundaryGroups,
        int temporalInversions,
        boolean leakageFree,
        Instant latestTrainTime,
        Instant earliestEvalTime,
        long judgingTotal,
        long judgingWithoutGroundTruth,
        long feedbackDerivedInJudging,
        long feedbackLabeledTotal,
        boolean noSelfJudge) {

    public static EvalIntegrityResponse from(EvalIntegrityReport report) {
        var tf = report.timeForward();
        var j = report.judging();
        return new EvalIntegrityResponse(
                report.holds(),
                tf.total(),
                tf.trainCount(),
                tf.evalCount(),
                tf.groupCount(),
                tf.crossBoundaryGroups(),
                tf.temporalInversions(),
                tf.leakageFree(),
                tf.latestTrainTime(),
                tf.earliestEvalTime(),
                j.judgingTotal(),
                j.judgingWithoutGroundTruth(),
                j.feedbackDerivedInJudging(),
                j.feedbackLabeledTotal(),
                j.noSelfJudge());
    }
}
