package com.antispam.eval;

/**
 * The anti-circularity evidence for the judging sets (story 11.03): proof that every email the system
 * judges against — the eval side of the split, the frozen golden set, and the rolling fresh set — is
 * graded by a high-confidence ground-truth label and <b>never</b> by simulator feedback. Feedback may
 * train (Epic 10.01) but must never judge; if it did, validation would be circular — the model would
 * be graded by signals it helped shape. This record is the number a reviewer points at to trust that
 * the loop is not grading itself.
 *
 * <p>The audit is computed by re-checking the judging sets against the label tables (not by trusting
 * the construction): {@link #judgingWithoutGroundTruth} and {@link #feedbackDerivedInJudging} are both
 * expected to be zero, and a regression that smuggled a feedback-only label into a judging set surfaces
 * as a non-zero count here.
 *
 * @param judgingTotal              distinct emails across all judging sets (eval side ∪ golden ∪ fresh)
 * @param judgingWithoutGroundTruth judging emails lacking a high-confidence ground-truth label — MUST
 *                                  be 0 ("eval ground truth = high-confidence labels only")
 * @param feedbackDerivedInJudging  judging emails whose only label source is simulator feedback (a
 *                                  feedback {@code retrain_label} and no ground truth) — MUST be 0
 *                                  (the anti-circularity guarantee); a subset of the above, called out
 *                                  separately because it is the specific failure the rule forbids
 * @param feedbackLabeledTotal      distinct emails carrying a feedback-sourced label at all — context,
 *                                  not a target: these exist and train, but none of them judges
 */
public record JudgingSourceAudit(
        long judgingTotal,
        long judgingWithoutGroundTruth,
        long feedbackDerivedInJudging,
        long feedbackLabeledTotal) {

    /** Whether no judging email is graded by feedback alone — the anti-circularity guarantee. */
    public boolean noSelfJudge() {
        return feedbackDerivedInJudging == 0;
    }

    /** Whether every judging email carries a high-confidence ground-truth label. */
    public boolean allHighConfidence() {
        return judgingWithoutGroundTruth == 0;
    }

    /** Whether the judging sets are sourced cleanly: high-confidence only, no feedback judging. */
    public boolean clean() {
        return noSelfJudge() && allHighConfidence();
    }
}
