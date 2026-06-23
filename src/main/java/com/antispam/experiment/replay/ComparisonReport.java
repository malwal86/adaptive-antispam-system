package com.antispam.experiment.replay;

/**
 * The controlled A/B comparison of two policies replayed over the same fixed corpus (story 09.04):
 * each policy's {@link PolicyMetrics} scorecard plus the {@link Deltas} between them. This is the
 * "evidence the promotion gate consumes" the story names — a programmatic object, every field a
 * primitive number, so Epic 10's precision-floor gate (story 10.03) reads {@code policyB.precision()}
 * against its floor and reports the deltas without reshaping anything.
 *
 * <p>By convention {@code policyA} is the baseline (the incumbent / control) and {@code policyB} is
 * the candidate (the challenger). Every delta is {@code B − A}: positive means the candidate scores
 * higher on that metric. Whether higher is better depends on the metric — higher precision/recall is
 * better, higher bypass-rate/cost/latency is worse — so the gate, not this report, decides direction.
 *
 * @param policyA the baseline policy's scorecard (control)
 * @param policyB the candidate policy's scorecard (challenger)
 * @param deltas  the per-metric {@code B − A} differences
 */
public record ComparisonReport(PolicyMetrics policyA, PolicyMetrics policyB, Deltas deltas) {

    /**
     * The per-metric {@code candidate − baseline} differences (story 09.04 AC 2). Positive means the
     * candidate (B) is higher on that metric; the consumer interprets whether that is an improvement.
     *
     * @param precision              {@code B.precision − A.precision}
     * @param recall                 {@code B.recall − A.recall}
     * @param bypassRate             {@code B.bypassRate − A.bypassRate} (positive = candidate leaks more)
     * @param llmEscalationRate      {@code B.llmEscalationRate − A.llmEscalationRate} (the cost delta)
     * @param estimatedLatencyMillis {@code B.estimatedLatencyMillis − A.estimatedLatencyMillis}
     */
    public record Deltas(
            double precision,
            double recall,
            double bypassRate,
            double llmEscalationRate,
            double estimatedLatencyMillis) {
    }

    /**
     * Builds the comparison from two scorecards, computing every delta as {@code B − A}.
     *
     * @param policyA the baseline (control) scorecard
     * @param policyB the candidate (challenger) scorecard
     * @return the paired scorecards and their per-metric deltas
     */
    public static ComparisonReport of(PolicyMetrics policyA, PolicyMetrics policyB) {
        Deltas deltas = new Deltas(
                policyB.precision() - policyA.precision(),
                policyB.recall() - policyA.recall(),
                policyB.bypassRate() - policyA.bypassRate(),
                policyB.llmEscalationRate() - policyA.llmEscalationRate(),
                policyB.estimatedLatencyMillis() - policyA.estimatedLatencyMillis());
        return new ComparisonReport(policyA, policyB, deltas);
    }
}
