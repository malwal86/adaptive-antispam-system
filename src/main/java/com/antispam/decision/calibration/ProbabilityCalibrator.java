package com.antispam.decision.calibration;

/**
 * Maps a model's raw probability to a <em>calibrated</em> probability — one whose
 * value can be read as a true frequency ("of the mails scored 0.7, about 70% really
 * are abuse"). Calibration is the hard precondition that makes the Bayesian fusion
 * of model score with sender reputation (story 04.04) mathematically valid: fusing
 * two numbers in log-odds space is only meaningful if each is an honest probability
 * (PRD §Subsystem 2).
 *
 * <p>A calibrator is a pure, deterministic function over {@code [0,1]}. The serving
 * path holds exactly one {@link #calibrate} away from the raw model output, swapped
 * atomically when a new calibration is fit (see {@code ActiveCalibrator}).
 */
@FunctionalInterface
public interface ProbabilityCalibrator {

    /**
     * Maps a raw probability to its calibrated value.
     *
     * @param rawProbability the model's raw probability in {@code [0,1]}
     * @return the calibrated probability in {@code [0,1]}
     */
    double calibrate(double rawProbability);

    /**
     * The do-nothing calibrator: returns the raw probability unchanged. It is the
     * serving default before any calibration has been fit, so the path is never in a
     * null state — an uncalibrated model simply serves its raw score until a fit
     * replaces this (Ousterhout: define the error out of existence).
     */
    static ProbabilityCalibrator identity() {
        return rawProbability -> rawProbability;
    }
}
