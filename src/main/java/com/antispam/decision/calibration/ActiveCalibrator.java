package com.antispam.decision.calibration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Holds the one calibrator the serving path is currently applying, and lets a freshly
 * fit calibration replace it atomically. The synchronous decision path reads it on
 * every model-route mail (via {@code ModelContentClassifier}); a calibration run
 * ({@code ModelCalibrationService}) installs a new fit from another thread. A single
 * {@code volatile} reference is the whole synchronisation: readers are lock-free and
 * always see either the old or the new calibrator, never a torn state — the same
 * lock-free-swap shape reputation uses (story 03.05).
 *
 * <p>Until the first successful fit, the active calibrator is the identity, so the model
 * serves its raw confidence rather than failing — there is no "uncalibrated" error state.
 */
@Component
public class ActiveCalibrator {

    private static final Logger log = LoggerFactory.getLogger(ActiveCalibrator.class);

    private volatile ProbabilityCalibrator calibrator = ProbabilityCalibrator.identity();

    /** Set once a fit calibration is installed; never goes back to false. */
    private volatile boolean calibrated = false;

    /** Calibrates a raw probability with whatever calibrator is currently installed. */
    public double calibrate(double rawProbability) {
        return calibrator.calibrate(rawProbability);
    }

    /**
     * Atomically replaces the serving calibrator. The next mail scored uses
     * {@code calibrator}; in-flight scores complete against the previous one.
     *
     * @param calibrator the newly fit calibrator to serve; must not be null
     */
    public void install(ProbabilityCalibrator calibrator) {
        if (calibrator == null) {
            throw new IllegalArgumentException("calibrator must not be null");
        }
        this.calibrator = calibrator;
        this.calibrated = true;
        log.info("active calibrator replaced with {}", calibrator.getClass().getSimpleName());
    }

    /** Whether a fit calibration has been installed (i.e. we are past the identity default). */
    public boolean isCalibrated() {
        return calibrated;
    }
}
