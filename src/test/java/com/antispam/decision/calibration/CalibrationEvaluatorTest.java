package com.antispam.decision.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.calibration.CalibrationEvaluator.Prediction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The ECE/reliability measurement is what the calibration gate trusts, so its
 * arithmetic is pinned on hand-built sets where the right answer is known by
 * construction: a perfectly honest set scores ~0, an overconfident set scores the
 * exact gap, and the headline number is the count-weighted average of the per-bin
 * gaps (story 04.02 AC 1).
 */
class CalibrationEvaluatorTest {

    private static final double EPS = 1e-9;

    @Test
    void perfectly_calibrated_predictions_have_zero_error() {
        // 0.0-scored items are all negative, 1.0-scored all positive: each bin's
        // predicted equals its observed frequency exactly.
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            predictions.add(new Prediction(0.0, false));
            predictions.add(new Prediction(1.0, true));
        }

        assertThat(CalibrationEvaluator.expectedCalibrationError(predictions, 10)).isCloseTo(0.0, offset());
    }

    @Test
    void a_uniformly_overconfident_set_scores_its_exact_gap() {
        // Everything scored 0.9, but only half are positive -> one populated bin with
        // gap |0.9 - 0.5| = 0.4, and since it holds 100% of the mass the ECE is 0.4.
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            predictions.add(new Prediction(0.9, i % 2 == 0));
        }

        assertThat(CalibrationEvaluator.expectedCalibrationError(predictions, 10)).isCloseTo(0.4, offset());
    }

    @Test
    void ece_is_the_count_weighted_average_of_per_bin_gaps() {
        // Bin [0.0,0.1): 30 items, all negative -> gap 0.05 (mean predicted 0.05).
        // Bin [0.9,1.0]: 10 items, all positive -> gap |0.95 - 1.0| = 0.05.
        // Weighted: (30/40)*0.05 + (10/40)*0.05 = 0.05.
        List<Prediction> predictions = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            predictions.add(new Prediction(0.05, false));
        }
        for (int i = 0; i < 10; i++) {
            predictions.add(new Prediction(0.95, true));
        }

        assertThat(CalibrationEvaluator.expectedCalibrationError(predictions, 10)).isCloseTo(0.05, offset());
    }

    @Test
    void reliability_curve_has_one_bin_per_partition_and_counts_sum_to_n() {
        List<Prediction> predictions = List.of(
                new Prediction(0.05, false),
                new Prediction(0.15, true),
                new Prediction(0.95, true),
                new Prediction(1.0, true));

        List<ReliabilityBin> curve = CalibrationEvaluator.reliabilityCurve(predictions, 10);

        assertThat(curve).hasSize(10);
        assertThat(curve.stream().mapToLong(ReliabilityBin::count).sum()).isEqualTo(4);
        // The probability 1.0 folds into the top bin, not a phantom 11th bin.
        assertThat(curve.get(9).count()).isEqualTo(2);
        assertThat(curve.get(9).observedFrequency()).isCloseTo(1.0, offset());
    }

    @Test
    void empty_predictions_have_zero_error() {
        assertThat(CalibrationEvaluator.expectedCalibrationError(List.of(), 10)).isEqualTo(0.0);
    }

    @Test
    void rejects_a_non_positive_bin_count() {
        assertThatThrownBy(() -> CalibrationEvaluator.reliabilityCurve(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_probability_outside_the_unit_interval() {
        assertThatThrownBy(() -> new Prediction(1.5, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static org.assertj.core.data.Offset<Double> offset() {
        return org.assertj.core.data.Offset.offset(EPS);
    }
}
