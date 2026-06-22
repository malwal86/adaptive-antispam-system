package com.antispam.decision.calibration.web;

import com.antispam.decision.calibration.CalibrationReportRepository.StoredReport;
import com.antispam.decision.calibration.ReliabilityBin;
import com.antispam.decision.calibration.ReliabilityReport;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The calibration evidence a viewer reads from {@code /model/calibration}: the
 * raw-vs-calibrated ECE (so the improvement is visible at a glance), the gate outcome,
 * and the calibrated reliability curve. This is the "the score is a true probability"
 * evidence the PRD's hard dependency demands (story 04.02 AC 3).
 *
 * @param reportId      the persisted run's id
 * @param modelVersion  the served artifact these scores came from
 * @param method        the calibration method fit (e.g. {@code isotonic})
 * @param sampleCount   held-out predictions the measurement is over
 * @param binCount      reliability-diagram resolution
 * @param eceRaw        expected calibration error of the raw scores
 * @param eceCalibrated expected calibration error after calibration
 * @param eceImprovement how much calibration lowered the error (positive means it helped)
 * @param maxEce        the ceiling this run was judged against
 * @param passed        whether it passed the gate (and was installed on the serving path)
 * @param reliabilityCurve the calibrated curve, one entry per bin
 * @param createdAt     when the run was recorded
 */
public record CalibrationReportResponse(
        UUID reportId,
        String modelVersion,
        String method,
        int sampleCount,
        int binCount,
        double eceRaw,
        double eceCalibrated,
        double eceImprovement,
        double maxEce,
        boolean passed,
        List<Bin> reliabilityCurve,
        Instant createdAt) {

    /** One point of the reliability diagram. */
    public record Bin(double lowerEdge, double upperEdge, long count,
            double meanPredicted, double observedFrequency) {

        static Bin from(ReliabilityBin bin) {
            return new Bin(bin.lowerEdge(), bin.upperEdge(), bin.count(),
                    bin.meanPredicted(), bin.observedFrequency());
        }
    }

    public static CalibrationReportResponse from(StoredReport stored) {
        ReliabilityReport report = stored.report();
        return new CalibrationReportResponse(
                stored.id(),
                report.modelVersion(),
                report.method(),
                report.sampleCount(),
                report.binCount(),
                report.eceRaw(),
                report.eceCalibrated(),
                report.eceImprovement(),
                stored.maxEce(),
                stored.passed(),
                report.calibratedBins().stream().map(Bin::from).toList(),
                stored.createdAt());
    }
}
