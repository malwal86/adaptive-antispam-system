package com.antispam.retrain;

import com.antispam.experiment.replay.PolicyMetrics;

/**
 * The non-blocking evidence the promotion gate reports alongside its precision verdict (story 10.03):
 * recall, the actual bypass rate, the cost (LLM escalation) pressure, and the latency estimate the
 * candidate earned on the golden set. The PRD is explicit that these are <b>reported, not
 * auto-blockers</b> — only precision gates promotion — so they live in a record distinct from the
 * pass/fail decision, present so a human (or 10.04's registry) can read the full trade-off the
 * candidate made, never consulted by {@link PrecisionFloorGate} to decide pass/fail.
 *
 * @param recall                 of the golden set's abuse, how much the candidate caught
 * @param bypassRate             of the golden set's abuse, how much the candidate delivered
 *                               (the {@code actual_bypass_rate} the story names)
 * @param llmEscalationRate      share of the golden set the candidate would escalate to the LLM
 *                               (the cost driver, Epic 05)
 * @param estimatedLatencyMillis mean nominal per-route latency over the golden set
 * @param abuseTotal             golden-set emails that are spam or phish (the recall/bypass base)
 */
public record GateEvidence(
        double recall,
        double bypassRate,
        double llmEscalationRate,
        double estimatedLatencyMillis,
        long abuseTotal) {

    /** Projects the reported (non-blocking) half of a scorecard out of a candidate's metrics. */
    public static GateEvidence from(PolicyMetrics metrics) {
        return new GateEvidence(
                metrics.recall(),
                metrics.bypassRate(),
                metrics.llmEscalationRate(),
                metrics.estimatedLatencyMillis(),
                metrics.abuseTotal());
    }
}
