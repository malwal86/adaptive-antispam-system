package com.antispam.decision.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The calibration gate's knobs, bound from {@code antispam.calibration} (story 04.02).
 * They live in config, not code, so the error ceiling and the diagram resolution can be
 * tuned without a redeploy.
 *
 * @param maxEce          the largest calibrated ECE a model may have and still be served;
 *                        a candidate above this is rejected (AC 4). In {@code [0,1]}.
 * @param bins            number of equal-width bins for the reliability diagram and ECE
 * @param minSamplesPerSide the fewest held-out points each split side must have before a
 *                        calibration is attempted; below this the measurement is too noisy
 *                        to trust, so the run is refused rather than producing a vacuous fit
 */
@Validated
@ConfigurationProperties(prefix = "antispam.calibration")
public record CalibrationProperties(double maxEce, int bins, int minSamplesPerSide) {

    public CalibrationProperties {
        if (maxEce < 0.0 || maxEce > 1.0) {
            throw new IllegalArgumentException(
                    "antispam.calibration.max-ece must be in [0, 1], was: " + maxEce);
        }
        if (bins < 1) {
            throw new IllegalArgumentException(
                    "antispam.calibration.bins must be at least 1, was: " + bins);
        }
        if (minSamplesPerSide < 1) {
            throw new IllegalArgumentException(
                    "antispam.calibration.min-samples-per-side must be at least 1, was: " + minSamplesPerSide);
        }
    }
}
