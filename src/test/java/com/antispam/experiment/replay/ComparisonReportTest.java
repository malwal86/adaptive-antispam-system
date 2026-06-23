package com.antispam.experiment.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The A/B comparison's contract (story 09.04): it pairs two scorecards as baseline (A) and candidate
 * (B) and reports every delta as {@code B − A}, so the promotion gate can read both absolute metrics
 * and the direction of change off one object.
 */
class ComparisonReportTest {

    private static LabeledReplayDecision labeled(Decision decision, RouteUsed route, GroundTruthLabel label) {
        return new LabeledReplayDecision(UUID.randomUUID(), decision, route, "v", label);
    }

    @Test
    void deltas_are_candidate_minus_baseline_for_every_metric() {
        // A (baseline): lets spam through. B (candidate): catches it, on the LLM route.
        PolicyMetrics a = PolicyMetrics.of("base-v1", "model-6", List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM)));
        PolicyMetrics b = PolicyMetrics.of("cand-v2", "model-7", List.of(
                labeled(Decision.BLOCK, RouteUsed.LLM, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM)));

        ComparisonReport report = ComparisonReport.of(a, b);

        assertThat(report.policyA()).isSameAs(a);
        assertThat(report.policyB()).isSameAs(b);
        assertThat(report.deltas().precision()).isEqualTo(b.precision() - a.precision());
        assertThat(report.deltas().recall()).isEqualTo(b.recall() - a.recall());
        assertThat(report.deltas().bypassRate()).isEqualTo(b.bypassRate() - a.bypassRate());
        assertThat(report.deltas().llmEscalationRate())
                .isEqualTo(b.llmEscalationRate() - a.llmEscalationRate());
        assertThat(report.deltas().estimatedLatencyMillis())
                .isEqualTo(b.estimatedLatencyMillis() - a.estimatedLatencyMillis());
    }

    @Test
    void candidate_that_catches_more_shows_positive_recall_and_negative_bypass_deltas() {
        PolicyMetrics a = PolicyMetrics.of("base-v1", "model-6", List.of(
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.SPAM)));
        PolicyMetrics b = PolicyMetrics.of("cand-v2", "model-7", List.of(
                labeled(Decision.BLOCK, RouteUsed.MODEL, GroundTruthLabel.SPAM)));

        ComparisonReport.Deltas deltas = ComparisonReport.of(a, b).deltas();

        assertThat(deltas.recall()).isEqualTo(1.0);
        assertThat(deltas.bypassRate()).isEqualTo(-1.0);
    }

    @Test
    void comparing_identical_scorecards_yields_zero_deltas() {
        // Two runs of the same policy over the same corpus (the determinism self-check) differ in
        // nothing — every delta is exactly zero.
        List<LabeledReplayDecision> corpus = List.of(
                labeled(Decision.BLOCK, RouteUsed.LLM, GroundTruthLabel.SPAM),
                labeled(Decision.ALLOW, RouteUsed.MODEL, GroundTruthLabel.HAM));
        PolicyMetrics a = PolicyMetrics.of("v", "m", corpus);
        PolicyMetrics b = PolicyMetrics.of("v", "m", corpus);

        ComparisonReport.Deltas deltas = ComparisonReport.of(a, b).deltas();

        assertThat(deltas.precision()).isCloseTo(0.0, within(1e-12));
        assertThat(deltas.recall()).isCloseTo(0.0, within(1e-12));
        assertThat(deltas.bypassRate()).isCloseTo(0.0, within(1e-12));
        assertThat(deltas.llmEscalationRate()).isCloseTo(0.0, within(1e-12));
        assertThat(deltas.estimatedLatencyMillis()).isCloseTo(0.0, within(1e-12));
    }
}
