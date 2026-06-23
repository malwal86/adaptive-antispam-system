package com.antispam.feedback.sensitivity;

import java.util.List;

/**
 * The result of a sensitivity sweep (story 07.04): the per-fraction curve plus the honest summary
 * the demo surfaces (AC 3/AC 4) — does the feedback defence hold as the adversarial population
 * grows, and where does it break?
 *
 * <p>{@code observedBreakdownFraction} is the smallest swept fraction at which a point was no longer
 * blunted, or {@code null} when the defence held across the whole sweep (a valid result — "no
 * breakdown within the swept range"). {@code breakdownBomberCount} is the <em>analytical</em> limit
 * independent of the sweep: the number of bombers of one vector at which their down-weighted reports
 * can first sum past the gate's weight floor ({@code ceil(minWeight / maliciousTrust)}). Reporting
 * the analytical limit alongside the observed one keeps the documented breakdown point honest even
 * when a small sweep does not reach it (AC 5).
 *
 * @param points                    the curve, one per swept fraction, in sweep order
 * @param tolerance                 the drift tolerance a point had to stay within to count as blunted
 * @param observedBreakdownFraction first swept fraction that was not blunted, or {@code null}
 * @param breakdownBomberCount      bombers-per-vector at which the gate's weight floor is first crossed
 */
public record SensitivityReport(
        List<SensitivityPoint> points,
        double tolerance,
        Double observedBreakdownFraction,
        int breakdownBomberCount) {

    public SensitivityReport {
        points = List.copyOf(points);
    }

    /**
     * Builds a report from the swept points, deriving the observed breakdown fraction (first
     * non-blunted point, in order).
     *
     * @param points               the curve in sweep order
     * @param tolerance            the blunted-drift tolerance used
     * @param breakdownBomberCount the analytical per-vector breakdown bomber count
     */
    public static SensitivityReport from(
            List<SensitivityPoint> points, double tolerance, int breakdownBomberCount) {
        Double observed = points.stream()
                .filter(point -> !point.blunted())
                .map(SensitivityPoint::maliciousFraction)
                .findFirst()
                .orElse(null);
        return new SensitivityReport(points, tolerance, observed, breakdownBomberCount);
    }
}
