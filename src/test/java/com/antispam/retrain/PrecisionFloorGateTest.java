package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.experiment.replay.PolicyMetrics;
import org.junit.jupiter.api.Test;

/**
 * The promotion gate's decision contract (story 10.03), pinned without a database on a constructed
 * scorecard so the arithmetic of pass/fail is unambiguous. The exact precision/recall/bypass numbers
 * are computed and proven in {@link com.antispam.experiment.replay.PolicyMetricsTest}; here we fix
 * those numbers and assert only what the gate adds: <b>precision is the hard floor</b>, every other
 * metric is reported evidence that never blocks, and the verdict is a pure function of the scorecard.
 */
class PrecisionFloorGateTest {

    private static final double FLOOR = 0.95;
    private static final int MIN_SAMPLES = 50;

    private static PrecisionFloorGate gate() {
        return new PrecisionFloorGate(new PrecisionGateProperties(FLOOR, MIN_SAMPLES));
    }

    @Test
    void passes_when_precision_clears_the_floor() {
        GateResult result = gate().evaluate(scorecard(0.97, 0.80, 0.20, 100));

        assertThat(result.passed()).isTrue();
        assertThat(result.precision()).isEqualTo(0.97);
        assertThat(result.precisionFloor()).isEqualTo(FLOOR);
    }

    @Test
    void passes_at_exactly_the_floor() {
        // The floor is inclusive: a candidate that matches it has not regressed below it.
        GateResult result = gate().evaluate(scorecard(FLOOR, 0.80, 0.20, 100));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void fails_when_precision_is_below_the_floor() {
        GateResult result = gate().evaluate(scorecard(0.94, 0.80, 0.20, 100));

        assertThat(result.passed()).isFalse();
        assertThat(result.precision()).isEqualTo(0.94);
    }

    @Test
    void rejects_a_high_recall_low_precision_candidate() {
        // The headline failure mode the gate exists to stop: a candidate that catches everything by
        // also blocking good mail. Precision is below the floor, so it fails regardless of recall.
        GateResult result = gate().evaluate(scorecard(0.50, 1.00, 0.00, 100));

        assertThat(result.passed()).isFalse();
        // The great recall is still reported as evidence — it just does not rescue the candidate.
        assertThat(result.evidence().recall()).isEqualTo(1.00);
    }

    @Test
    void recall_cost_and_bypass_are_reported_but_never_block() {
        // A candidate that clears the precision floor passes even with poor recall, a high bypass
        // rate, and high cost — those are reported evidence, not auto-blockers (AC 4).
        PolicyMetrics weakEvidence = new PolicyMetrics(
                "cand", "model-x", 100, 40, 60, 4, 0, 36,
                /* precision */ 1.00, /* recall */ 0.10, /* bypassRate */ 0.90,
                /* llmEscalationCount */ 90, /* llmEscalationRate */ 0.90,
                /* estimatedLatencyMillis */ 810.0);

        GateResult result = gate().evaluate(weakEvidence);

        assertThat(result.passed()).isTrue();
        assertThat(result.evidence().recall()).isEqualTo(0.10);
        assertThat(result.evidence().bypassRate()).isEqualTo(0.90);
        assertThat(result.evidence().llmEscalationRate()).isEqualTo(0.90);
        assertThat(result.evidence().estimatedLatencyMillis()).isEqualTo(810.0);
        assertThat(result.evidence().abuseTotal()).isEqualTo(40);
    }

    @Test
    void carries_the_candidate_policy_and_model_version_for_the_record() {
        GateResult result = gate().evaluate(scorecard(0.97, 0.80, 0.20, 100));

        assertThat(result.policyVersion()).isEqualTo("cand");
        assertThat(result.modelVersion()).isEqualTo("model-x");
        assertThat(result.goldenSampleCount()).isEqualTo(100);
    }

    @Test
    void fails_when_the_golden_set_is_too_small_to_judge_even_at_perfect_precision() {
        // Precision on a handful of emails is noise. Below the sample floor the gate fails with a
        // distinct reason rather than passing on an unreliable measurement (mirrors calibration's
        // min-samples-per-side). The candidate here has perfect precision and still does not pass.
        GateResult result = gate().evaluate(scorecard(1.00, 1.00, 0.00, MIN_SAMPLES - 1));

        assertThat(result.passed()).isFalse();
        assertThat(result.reason()).containsIgnoringCase("golden set");
    }

    @Test
    void passes_at_exactly_the_minimum_golden_sample_count() {
        GateResult result = gate().evaluate(scorecard(0.97, 0.80, 0.20, MIN_SAMPLES));

        assertThat(result.passed()).isTrue();
    }

    @Test
    void is_deterministic_the_same_scorecard_yields_the_same_verdict() {
        PolicyMetrics same = scorecard(0.97, 0.80, 0.20, 100);

        assertThat(gate().evaluate(same)).isEqualTo(gate().evaluate(same));
    }

    /**
     * A scorecard fixing the only fields the gate reads — precision (the floor), the reported
     * evidence, and the sample count. The catch-count fields the gate ignores are left at zero; the
     * real arithmetic linking them is proven in {@code PolicyMetricsTest}.
     */
    private static PolicyMetrics scorecard(double precision, double recall, double bypassRate, long total) {
        return new PolicyMetrics(
                "cand", "model-x", total, 0, 0, 0, 0, 0,
                precision, recall, bypassRate, 0, 0.0, 0.0);
    }
}
