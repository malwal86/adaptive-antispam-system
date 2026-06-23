package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The metric computation's contract (story 09.04): grading a run's labeled decisions into a
 * per-policy scorecard. "Withheld" (a positive prediction) means a non-delivering tier
 * (QUARANTINE/BLOCK); abuse is SPAM or PHISH. From that, precision = withheld-abuse / all-withheld,
 * recall = withheld-abuse / all-abuse, bypass = delivered-abuse / all-abuse, cost is the LLM
 * escalation share, and latency is the mean nominal per-route cost. Degenerate corpora (nothing
 * withheld, no abuse, empty) resolve to 0 rather than a divide-by-zero.
 */
class PolicyMetricsTest {

    private static LabeledReplayDecision labeled(Decision decision, RouteUsed route, GroundTruthLabel label) {
        return new LabeledReplayDecision(UUID.randomUUID(), decision, route, "cand-v2", label);
    }

    @Test
    void computes_precision_recall_and_bypass_from_withheld_vs_abuse() {
        // TP=2 (spam BLOCK, phish QUARANTINE), FN=1 (phish ALLOW), FP=1 (ham BLOCK), TN=3 (ham deliver).
        List<LabeledReplayDecision> decisions = List.of(
                labeled(Decision.BLOCK, RouteUsed.MODEL, GroundTruthLabel.SPAM),
                labeled(Decision.QUARANTINE, RouteUsed.MODEL, GroundTruthLabel.PHISH),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.PHISH),
                labeled(Decision.BLOCK, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.WARN, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM));

        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", decisions);

        assertThat(metrics.policyVersion()).isEqualTo("cand-v2");
        assertThat(metrics.modelVersion()).isEqualTo("model-7");
        assertThat(metrics.total()).isEqualTo(7);
        assertThat(metrics.abuseTotal()).isEqualTo(3);
        assertThat(metrics.hamTotal()).isEqualTo(4);
        assertThat(metrics.truePositives()).isEqualTo(2);
        assertThat(metrics.falsePositives()).isEqualTo(1);
        assertThat(metrics.falseNegatives()).isEqualTo(1);
        assertThat(metrics.precision()).isEqualTo(2.0 / 3.0);
        assertThat(metrics.recall()).isEqualTo(2.0 / 3.0);
        assertThat(metrics.bypassRate()).isEqualTo(1.0 / 3.0);
    }

    @Test
    void warn_delivers_so_abuse_warned_is_a_bypass_not_a_catch() {
        // WARN delivers (with a banner), exactly as Decision.delivers() and the live path treat it:
        // spam that only earns WARN got through, so it is a false negative, not a true positive.
        List<LabeledReplayDecision> decisions = List.of(
                labeled(Decision.WARN, RouteUsed.MODEL, GroundTruthLabel.SPAM));

        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", decisions);

        assertThat(metrics.truePositives()).isZero();
        assertThat(metrics.falseNegatives()).isEqualTo(1);
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.bypassRate()).isEqualTo(1.0);
    }

    @Test
    void cost_is_the_llm_escalation_share_and_latency_the_mean_nominal_route_cost() {
        // 1 LLM + 1 MODEL + 2 HARD_RULE over 4 emails: escalation rate 1/4; latency mean of the
        // nominal per-route model (LLM 900 + MODEL 20 + HARD_RULE 1 + HARD_RULE 1) / 4.
        List<LabeledReplayDecision> decisions = List.of(
                labeled(Decision.BLOCK, RouteUsed.LLM, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.ALLOW, RouteUsed.HARD_RULE, GroundTruthLabel.HAM),
                labeled(Decision.QUARANTINE, RouteUsed.HARD_RULE, GroundTruthLabel.SPAM));

        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", decisions);

        assertThat(metrics.llmEscalationCount()).isEqualTo(1);
        assertThat(metrics.llmEscalationRate()).isEqualTo(0.25);
        assertThat(metrics.estimatedLatencyMillis()).isEqualTo((900.0 + 20.0 + 1.0 + 1.0) / 4.0);
    }

    @Test
    void precision_is_zero_when_the_policy_withholds_nothing() {
        // A policy that delivers everything has no demonstrated precision (TP+FP=0); it must read 0
        // so it cannot clear a positive precision floor in the promotion gate (story 10.03).
        List<LabeledReplayDecision> decisions = List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM));

        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", decisions);

        assertThat(metrics.precision()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.bypassRate()).isEqualTo(1.0);
    }

    @Test
    void recall_and_bypass_are_zero_when_the_corpus_has_no_abuse() {
        List<LabeledReplayDecision> decisions = List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.BLOCK, RouteUsed.MODEL, GroundTruthLabel.HAM));

        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", decisions);

        assertThat(metrics.abuseTotal()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.bypassRate()).isZero();
        // The blocked ham is still a false positive — precision reflects it.
        assertThat(metrics.falsePositives()).isEqualTo(1);
        assertThat(metrics.precision()).isZero();
    }

    @Test
    void an_empty_run_is_all_zeros_not_a_divide_by_zero() {
        PolicyMetrics metrics = PolicyMetrics.of("cand-v2", "model-7", List.of());

        assertThat(metrics.total()).isZero();
        assertThat(metrics.precision()).isZero();
        assertThat(metrics.recall()).isZero();
        assertThat(metrics.bypassRate()).isZero();
        assertThat(metrics.llmEscalationRate()).isZero();
        assertThat(metrics.estimatedLatencyMillis()).isZero();
    }

    @Test
    void grading_is_order_independent_so_metrics_are_deterministic() {
        List<LabeledReplayDecision> forward = List.of(
                labeled(Decision.BLOCK, RouteUsed.LLM, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM),
                labeled(Decision.QUARANTINE, RouteUsed.HARD_RULE, GroundTruthLabel.PHISH));
        List<LabeledReplayDecision> reversed = List.of(
                forward.get(2), forward.get(1), forward.get(0));

        PolicyMetrics a = PolicyMetrics.of("cand-v2", "model-7", forward);
        PolicyMetrics b = PolicyMetrics.of("cand-v2", "model-7", reversed);

        assertThat(a).isEqualTo(b);
    }
}
