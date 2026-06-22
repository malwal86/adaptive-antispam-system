package com.antispam.decision.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The gate is the line between a trustworthy model and a meaningless fusion (story
 * 04.02 AC 4): a well-calibrated candidate passes, a deliberately miscalibrated one is
 * rejected, and the boundary itself is pinned so the ceiling is inclusive, not vague.
 */
class CalibrationGateTest {

    @Test
    void passes_a_model_within_the_error_ceiling() {
        ReliabilityReport report = report(0.20, 0.03);

        CalibrationGate.Verdict verdict = CalibrationGate.evaluate(report, 0.05);

        assertThat(verdict.passed()).isTrue();
        assertThat(verdict.reason()).contains("within");
    }

    @Test
    void rejects_a_deliberately_miscalibrated_model() {
        // Calibration barely moved the error and it is still far above the ceiling.
        ReliabilityReport report = report(0.40, 0.38);

        CalibrationGate.Verdict verdict = CalibrationGate.evaluate(report, 0.05);

        assertThat(verdict.passed()).isFalse();
        assertThat(verdict.reason()).contains("exceeds");
    }

    @Test
    void treats_the_ceiling_as_inclusive() {
        ReliabilityReport report = report(0.10, 0.05);

        assertThat(CalibrationGate.evaluate(report, 0.05).passed()).isTrue();
    }

    @Test
    void rejects_a_threshold_outside_the_unit_interval() {
        assertThatThrownBy(() -> CalibrationGate.evaluate(report(0.1, 0.05), 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ReliabilityReport report(double eceRaw, double eceCalibrated) {
        return new ReliabilityReport("bootstrap-v1", "isotonic", 500, 10, eceRaw, eceCalibrated, List.of());
    }
}
