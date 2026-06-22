package com.antispam.decision.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/**
 * The isotonic fit is the heart of calibration, so it is pinned as a pure unit: the
 * mapping it learns must be monotonic, stay a probability, recover the empirical
 * frequency of a score region, and be reproducible. The keystone case proves the
 * point of the whole story — on a deliberately miscalibrated set, the calibrated
 * scores are measurably closer to the truth than the raw ones (story 04.02 AC 1).
 */
class IsotonicCalibratorTest {

    @Test
    void calibrated_output_is_monotonic_non_decreasing_in_the_raw_score() {
        // A clean separation: low scores are mostly ham, high scores mostly abuse.
        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(List.of(
                new LabeledScore(0.1, false),
                new LabeledScore(0.2, false),
                new LabeledScore(0.4, true),
                new LabeledScore(0.5, false),
                new LabeledScore(0.8, true),
                new LabeledScore(0.9, true)));

        double previous = -1.0;
        for (double raw = 0.0; raw <= 1.0; raw += 0.01) {
            double value = calibrator.calibrate(raw);
            assertThat(value).isGreaterThanOrEqualTo(previous);
            previous = value;
        }
    }

    @Test
    void calibrated_output_stays_within_the_unit_interval() {
        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(List.of(
                new LabeledScore(0.0, false),
                new LabeledScore(0.3, true),
                new LabeledScore(0.7, false),
                new LabeledScore(1.0, true)));

        for (double raw = 0.0; raw <= 1.0; raw += 0.05) {
            assertThat(calibrator.calibrate(raw)).isBetween(0.0, 1.0);
        }
    }

    @Test
    void recovers_the_empirical_positive_frequency_of_a_score_cluster() {
        // Forty points all scored 0.9 by the model, but only a quarter are truly
        // positive — the model is overconfident here. Isotonic should learn ~0.25.
        List<LabeledScore> points = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            points.add(new LabeledScore(0.9, i < 10));
        }

        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(points);

        assertThat(calibrator.calibrate(0.9)).isCloseTo(0.25, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void clamps_inputs_beyond_the_fitted_range_to_the_end_values() {
        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(List.of(
                new LabeledScore(0.3, false),
                new LabeledScore(0.3, false),
                new LabeledScore(0.7, true),
                new LabeledScore(0.7, true)));

        // Below the lowest threshold -> lowest fitted value; above the highest -> highest.
        assertThat(calibrator.calibrate(0.0)).isEqualTo(calibrator.calibrate(0.3));
        assertThat(calibrator.calibrate(1.0)).isEqualTo(calibrator.calibrate(0.7));
    }

    @Test
    void interpolates_linearly_between_two_breakpoints() {
        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(List.of(
                new LabeledScore(0.2, false),
                new LabeledScore(0.8, true)));

        // Breakpoints (0.2 -> 0.0) and (0.8 -> 1.0); the midpoint 0.5 is halfway.
        assertThat(calibrator.calibrate(0.5)).isCloseTo(0.5, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void calibrated_scores_have_lower_calibration_error_than_raw_on_a_miscalibrated_set() {
        // Build a model that is right about the ORDERING but wrong about the
        // MAGNITUDE: the true abuse probability is raw^2, so the raw score badly
        // overstates risk in the middle band. A calibrator fit on a held-out half
        // should pull the served scores back toward the truth.
        Random rng = new Random(20260622L);
        List<LabeledScore> fitSet = miscalibrated(rng, 4000);
        List<LabeledScore> holdout = miscalibrated(rng, 4000);

        IsotonicCalibrator calibrator = IsotonicCalibrator.fit(fitSet);

        List<CalibrationEvaluator.Prediction> raw = new ArrayList<>();
        List<CalibrationEvaluator.Prediction> calibrated = new ArrayList<>();
        for (LabeledScore point : holdout) {
            raw.add(new CalibrationEvaluator.Prediction(point.score(), point.positive()));
            calibrated.add(new CalibrationEvaluator.Prediction(
                    calibrator.calibrate(point.score()), point.positive()));
        }

        double eceRaw = CalibrationEvaluator.expectedCalibrationError(raw, 10);
        double eceCalibrated = CalibrationEvaluator.expectedCalibrationError(calibrated, 10);

        assertThat(eceCalibrated).isLessThan(eceRaw);
        assertThat(eceCalibrated).isLessThan(0.05);
    }

    @Test
    void same_points_fit_the_same_calibrator() {
        List<LabeledScore> points = List.of(
                new LabeledScore(0.1, false),
                new LabeledScore(0.6, true),
                new LabeledScore(0.6, false),
                new LabeledScore(0.9, true));

        IsotonicCalibrator a = IsotonicCalibrator.fit(points);
        IsotonicCalibrator b = IsotonicCalibrator.fit(points);

        assertThat(a.thresholds()).containsExactly(b.thresholds());
        assertThat(a.calibratedValues()).containsExactly(b.calibratedValues());
    }

    @Test
    void rejects_an_empty_training_set() {
        assertThatThrownBy(() -> IsotonicCalibrator.fit(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** A draw where the raw score predicts abuse with true probability {@code raw^2}. */
    private static List<LabeledScore> miscalibrated(Random rng, int n) {
        List<LabeledScore> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            double raw = rng.nextDouble();
            boolean positive = rng.nextDouble() < raw * raw;
            out.add(new LabeledScore(raw, positive));
        }
        return out;
    }
}
