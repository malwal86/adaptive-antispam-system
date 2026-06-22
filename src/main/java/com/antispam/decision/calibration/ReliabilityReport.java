package com.antispam.decision.calibration;

import java.util.List;

/**
 * The measured reliability of a model's scores on a held-out set, tagged with the
 * {@code model_version} that produced them. It pairs the raw and calibrated ECE — so
 * the improvement calibration bought is visible at a glance — with the calibrated
 * reliability curve as supporting evidence (story 04.02 AC 3). This is a pure
 * measurement: it records what is true of the model, not whether that is good enough.
 * The {@link CalibrationGate} applies the threshold that turns it into a pass/fail.
 *
 * @param modelVersion   the served-artifact identifier these scores came from
 * @param method         the calibration method fit (e.g. {@code isotonic})
 * @param sampleCount    number of held-out predictions the measurement is over
 * @param binCount       number of equal-width bins used for the curve and ECE
 * @param eceRaw         expected calibration error of the raw model scores
 * @param eceCalibrated  expected calibration error after calibration
 * @param calibratedBins the calibrated reliability curve, one entry per bin
 */
public record ReliabilityReport(
        String modelVersion,
        String method,
        int sampleCount,
        int binCount,
        double eceRaw,
        double eceCalibrated,
        List<ReliabilityBin> calibratedBins) {

    public ReliabilityReport {
        calibratedBins = List.copyOf(calibratedBins);
    }

    /** How much calibration lowered the error; positive means it helped. */
    public double eceImprovement() {
        return eceRaw - eceCalibrated;
    }
}
