package com.antispam.decision.calibration;

/**
 * One training point for fitting a calibrator: a model's raw probability paired with
 * the ground truth it was scoring. {@code positive} is the binary event the
 * probability predicts — here, "this mail is abuse (spam or phish), not ham". An
 * isotonic fit over many of these learns, for each score region, the empirical
 * frequency of {@code positive}, which is exactly the calibrated probability.
 *
 * @param score    the model's raw probability in {@code [0,1]}
 * @param positive whether the event the score predicts actually occurred
 */
public record LabeledScore(double score, boolean positive) {

    public LabeledScore {
        if (score < 0.0 || score > 1.0 || Double.isNaN(score)) {
            throw new IllegalArgumentException("score must be a probability in [0,1] but was " + score);
        }
    }
}
