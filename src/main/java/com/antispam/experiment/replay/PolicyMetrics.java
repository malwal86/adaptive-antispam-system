package com.antispam.experiment.replay;

import com.antispam.decision.RouteUsed;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;

/**
 * The per-policy scorecard of one replay run over the fixed labeled corpus (story 09.04): the
 * precision, recall, bypass-rate, cost, and latency a policy earns when its replayed decisions are
 * graded against ground truth. Tagged with the {@code policyVersion} and {@code modelVersion} it was
 * scored under so two scorecards are version-comparable — this is exactly the per-policy half of the
 * A/B comparison the promotion gate (Epic 10) consumes.
 *
 * <p><b>What counts as a catch.</b> An email is "withheld" (a positive prediction) when its tier
 * does not deliver — {@code QUARANTINE} or {@code BLOCK} (see {@link com.antispam.decision.Decision#delivers()}).
 * {@code WARN} delivers (with a banner) and so counts as letting the mail through, the same way the
 * live path treats it. Ground-truth abuse is {@code SPAM} or {@code PHISH}; {@code HAM} is legitimate.
 * From that: a true positive is withheld abuse, a false positive is withheld ham (blocked good mail),
 * and a false negative is delivered abuse (a bypass).
 *
 * <ul>
 *   <li><b>precision</b> = TP / (TP + FP) — of the mail this policy withheld, how much was actually
 *       abuse. The promotion gate's hard floor (story 10.03). {@code 0} when the policy withholds
 *       nothing (TP + FP = 0): a policy that flags no mail has no demonstrated precision and must not
 *       clear a positive floor.</li>
 *   <li><b>recall</b> = TP / (TP + FN) — of the actual abuse, how much this policy caught. {@code 0}
 *       when the corpus has no abuse to catch.</li>
 *   <li><b>bypassRate</b> = FN / abuseTotal — of the actual abuse, how much this policy delivered.
 *       The {@code actual_bypass_rate} evidence the gate reports (story 10.03). {@code 0} when the
 *       corpus has no abuse.</li>
 *   <li><b>cost</b> — the LLM is the only metered cost lever (Epic 05), and the replay path never
 *       spends it (it reports where it <em>would</em> escalate; {@link PolicyScorer}). So the
 *       deterministic cost signal is the escalation pressure: {@code llmEscalationCount} and its rate
 *       over the corpus.</li>
 *   <li><b>estimatedLatencyMillis</b> — a deterministic estimate, not measured wall-clock (which
 *       would vary run to run and break the determinism replay guarantees). It is the mean of a fixed
 *       nominal per-route cost: a hard-rule short-circuit is ~free, the model path costs a model
 *       inference, an LLM escalation costs a provider round-trip that dominates the tail.</li>
 * </ul>
 *
 * @param policyVersion          the policy these decisions were scored under
 * @param modelVersion           the model artifact that policy is calibrated for (Epic 10 tag)
 * @param total                  labeled emails scored under this policy (the comparison denominator)
 * @param abuseTotal             labeled emails that are spam or phish (the recall/bypass denominator)
 * @param hamTotal               labeled emails that are ham
 * @param truePositives          withheld abuse (correct catches)
 * @param falsePositives         withheld ham (blocked good mail)
 * @param falseNegatives         delivered abuse (bypasses)
 * @param precision              TP / (TP + FP), or 0 when nothing was withheld
 * @param recall                 TP / (TP + FN), or 0 when there is no abuse
 * @param bypassRate             FN / abuseTotal, or 0 when there is no abuse
 * @param llmEscalationCount     decisions the policy would escalate to the LLM (the cost driver)
 * @param llmEscalationRate      {@code llmEscalationCount / total}, or 0 when nothing was scored
 * @param estimatedLatencyMillis the mean nominal per-route latency over the scored emails
 */
public record PolicyMetrics(
        String policyVersion,
        String modelVersion,
        long total,
        long abuseTotal,
        long hamTotal,
        long truePositives,
        long falsePositives,
        long falseNegatives,
        double precision,
        double recall,
        double bypassRate,
        long llmEscalationCount,
        double llmEscalationRate,
        double estimatedLatencyMillis) {

    /**
     * Nominal per-route latency in milliseconds — a fixed, documented cost model, not a measurement.
     * The relative magnitudes are what matter to an A/B: a hard-rule short-circuit is near-free, the
     * model path costs an inference, and an LLM escalation costs a provider round-trip that dominates
     * the tail. Holding them constant keeps the latency metric deterministic across re-runs.
     */
    private static double nominalLatencyMillis(RouteUsed route) {
        return switch (route) {
            case HARD_RULE -> 1.0;
            case MODEL -> 20.0;
            case LLM -> 900.0;
        };
    }

    /**
     * Grades a single run's labeled decisions into a scorecard, tagged with the policy and model
     * versions for comparability. Pure and order-independent: the same labeled set always yields the
     * same metrics, which is the determinism the A/B comparison and the promotion gate rely on.
     *
     * @param policyVersion the policy the decisions were scored under
     * @param modelVersion  the model artifact that policy is calibrated for
     * @param decisions     the run's decisions joined to ground truth (only labeled emails)
     * @return the precision/recall/bypass/cost/latency scorecard for this policy
     */
    public static PolicyMetrics of(
            String policyVersion, String modelVersion, List<LabeledReplayDecision> decisions) {
        long abuseTotal = 0;
        long hamTotal = 0;
        long truePositives = 0;
        long falsePositives = 0;
        long falseNegatives = 0;
        long llmEscalationCount = 0;
        double latencySum = 0.0;

        for (LabeledReplayDecision d : decisions) {
            boolean withheld = !d.decision().delivers();
            boolean abuse = isAbuse(d.label());
            if (abuse) {
                abuseTotal++;
                if (withheld) {
                    truePositives++;
                } else {
                    falseNegatives++;
                }
            } else {
                hamTotal++;
                if (withheld) {
                    falsePositives++;
                }
            }
            if (d.route() == RouteUsed.LLM) {
                llmEscalationCount++;
            }
            latencySum += nominalLatencyMillis(d.route());
        }

        long total = decisions.size();
        long withheldTotal = truePositives + falsePositives;
        double precision = withheldTotal == 0 ? 0.0 : (double) truePositives / withheldTotal;
        double recall = abuseTotal == 0 ? 0.0 : (double) truePositives / abuseTotal;
        double bypassRate = abuseTotal == 0 ? 0.0 : (double) falseNegatives / abuseTotal;
        double llmEscalationRate = total == 0 ? 0.0 : (double) llmEscalationCount / total;
        double estimatedLatencyMillis = total == 0 ? 0.0 : latencySum / total;

        return new PolicyMetrics(
                policyVersion, modelVersion, total, abuseTotal, hamTotal,
                truePositives, falsePositives, falseNegatives,
                precision, recall, bypassRate,
                llmEscalationCount, llmEscalationRate, estimatedLatencyMillis);
    }

    private static boolean isAbuse(GroundTruthLabel label) {
        return label == GroundTruthLabel.SPAM || label == GroundTruthLabel.PHISH;
    }
}
