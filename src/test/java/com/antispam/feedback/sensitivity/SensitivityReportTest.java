package com.antispam.feedback.sensitivity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Breakdown detection over a swept curve (story 07.04): the observed breakdown is the first
 * non-blunted fraction in sweep order, or none when the defence held throughout.
 */
class SensitivityReportTest {

    private static SensitivityPoint point(double fraction, boolean blunted) {
        return new SensitivityPoint(fraction, 10, (int) (fraction * 10), 0.0, 0.0, blunted);
    }

    @Test
    void no_breakdown_when_every_point_is_blunted() {
        SensitivityReport report = SensitivityReport.from(
                List.of(point(0.0, true), point(0.2, true), point(0.4, true)), 0.5, 15);
        assertThat(report.observedBreakdownFraction()).isNull();
    }

    @Test
    void breakdown_is_the_first_non_blunted_fraction() {
        SensitivityReport report = SensitivityReport.from(
                List.of(point(0.0, true), point(0.3, false), point(0.4, false)), 0.5, 15);
        assertThat(report.observedBreakdownFraction()).isEqualTo(0.3);
    }

    @Test
    void carries_the_analytical_breakdown_bomber_count() {
        SensitivityReport report = SensitivityReport.from(List.of(point(0.0, true)), 0.5, 15);
        assertThat(report.breakdownBomberCount()).isEqualTo(15);
        assertThat(report.tolerance()).isEqualTo(0.5);
    }
}
