package com.antispam.decision.calibration;

/**
 * The promotion gate for calibration (story 04.02 AC 4): a model whose calibrated
 * scores are still less reliable than an agreed ceiling is rejected, because fusing an
 * un-calibratable score with reputation would be mathematically meaningless (PRD
 * §Subsystem 2). The same check the retrain loop (Epic 10) runs before promoting a
 * candidate — so an untrustworthy model never reaches the serving path.
 *
 * <p>A pure policy over a {@link ReliabilityReport}: it adds the threshold the report
 * deliberately does not carry, and reports <em>why</em> it passed or failed so the
 * verdict is self-explaining evidence.
 */
public final class CalibrationGate {

    private CalibrationGate() {
    }

    /**
     * The outcome of the gate.
     *
     * @param passed whether the calibrated ECE is within the ceiling
     * @param reason a human-readable justification, citing the numbers compared
     */
    public record Verdict(boolean passed, String reason) {
    }

    /**
     * Judges a report against the maximum tolerated calibrated ECE.
     *
     * @param report the measured reliability of a candidate model
     * @param maxEce the largest calibrated ECE that may still be served, in {@code [0,1]}
     * @return a pass when {@code report.eceCalibrated() <= maxEce}, else a fail
     * @throws IllegalArgumentException if {@code maxEce} is not in {@code [0,1]}
     */
    public static Verdict evaluate(ReliabilityReport report, double maxEce) {
        if (maxEce < 0.0 || maxEce > 1.0 || Double.isNaN(maxEce)) {
            throw new IllegalArgumentException("maxEce must be in [0,1] but was " + maxEce);
        }
        boolean passed = report.eceCalibrated() <= maxEce;
        String reason = String.format(
                "calibrated ECE %.4f %s ceiling %.4f for model %s (raw ECE was %.4f)",
                report.eceCalibrated(), passed ? "within" : "exceeds", maxEce,
                report.modelVersion(), report.eceRaw());
        return new Verdict(passed, reason);
    }
}
