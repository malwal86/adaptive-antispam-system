package com.antispam.feedback.sensitivity;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The malicious-fraction sensitivity sweep end-to-end against real Postgres (story 07.04): bombers
 * are run through the real simulator (07.02) and gate (07.03), and the curve shows the defence
 * holding. It pins the ACs: report bombers do not drive a legit sender's reputation down (AC 1) and
 * rescue bombers do not inflate a spam sender's (AC 2) while below the gate's breakdown; the sweep
 * is reproducible (AC 4); and the report carries the documented analytical breakdown point (AC 5).
 *
 * <p>The assertions lean on the gate's <em>structural</em> guarantee rather than on lucky draws:
 * below {@code ceil(minWeight / maliciousTrust)} distinct bombers per vector, their down-weighted
 * reports cannot clear the weight floor, so drift is exactly zero for any seed. The sweep here stays
 * under that count, so every point is blunted with zero drift.
 */
class FeedbackSensitivityIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private FeedbackSensitivityService service;

    private static final SweepSpec SMALL_SWEEP =
            new SweepSpec(42L, 12, List.of(0.0, 0.25, 0.5), 6);

    @Test
    void the_defence_holds_across_the_swept_range_with_zero_drift() {
        SensitivityReport report = service.sweep(SMALL_SWEEP);

        assertThat(report.points()).hasSize(3);
        assertThat(report.points()).allSatisfy(point -> {
            assertThat(point.blunted()).isTrue();
            assertThat(point.hamReputationDrift()).isZero();   // report bombers blunted (AC 1)
            assertThat(point.spamPromotionDrift()).isZero();   // rescue bombers blunted (AC 2)
        });
        // The bomber count grows with the fraction (0, 3, 6 of 12) — the population really is varied.
        assertThat(report.points().get(0).bomberCount()).isZero();
        assertThat(report.points().get(2).bomberCount()).isEqualTo(6);
        // Documented analytical breakdown: ceil(minWeight 1.5 / maliciousTrust 0.1) = 15 bombers per
        // vector, well above this sweep — so no observed breakdown, honestly reported as null (AC 5).
        assertThat(report.breakdownBomberCount()).isEqualTo(15);
        assertThat(report.observedBreakdownFraction()).isNull();
    }

    @Test
    void the_sweep_is_reproducible() {
        SensitivityReport first = service.sweep(SMALL_SWEEP);
        SensitivityReport second = service.sweep(SMALL_SWEEP);
        // Same spec → same curve. Senders/personas are freshly scoped per run, but the reported
        // fractions, counts, drifts, and breakdown are a deterministic function of the seed (AC 4).
        assertThat(second).isEqualTo(first);
    }
}
