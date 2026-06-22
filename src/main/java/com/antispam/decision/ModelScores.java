package com.antispam.decision;

/**
 * The classifier's contribution to a decision: the raw model probabilities, the
 * calibrated confidence derived from them, and the version of the model that produced
 * them. Present only on outcomes decided by the {@link RouteUsed#MODEL} path — a
 * hard-rule short-circuit never invokes the model, so its {@link DecisionOutcome}
 * carries no scores.
 *
 * <p><b>Raw vs calibrated.</b> {@link #spamScore()} and {@link #phishingScore()} are
 * the model's raw, uncalibrated 3-class outputs. {@link #calibratedConfidence()} is the
 * single "this mail is abuse" probability — {@code P(spam or phish)} — after the active
 * calibrator has corrected it into a true frequency (story 04.02). It is the calibrated
 * confidence the fusion stage (04.04) combines with sender reputation in log-odds space;
 * fusing requires a calibrated probability, which is why this field exists distinct from
 * the raw scores.
 *
 * <p>The {@link OnnxModel} populates {@code calibratedConfidence} with the raw
 * {@link #rawMalicious()} value (an identity calibration), and the serving classifier
 * replaces it via {@link #withCalibratedConfidence(double)} using whichever calibrator is
 * currently active — so an un-calibrated model still returns a coherent confidence.
 *
 * @param spamScore            raw P(spam) in {@code [0,1]} from the 3-class model
 * @param phishingScore        raw P(phish) in {@code [0,1]} from the 3-class model
 * @param modelVersion         identifier of the served model artifact (e.g. {@code bootstrap-v1})
 * @param calibratedConfidence calibrated P(abuse) in {@code [0,1]}
 */
public record ModelScores(double spamScore, double phishingScore, String modelVersion,
        double calibratedConfidence) {

    /**
     * Scores straight off the model, before calibration: the calibrated confidence
     * defaults to the raw {@link #rawMalicious()} value (identity calibration). The
     * serving path overrides it with {@link #withCalibratedConfidence(double)}.
     */
    public ModelScores(double spamScore, double phishingScore, String modelVersion) {
        this(spamScore, phishingScore, modelVersion, clampUnit(spamScore + phishingScore));
    }

    /**
     * The raw, uncalibrated probability that the mail is abuse: {@code P(spam) + P(phish)}
     * {@code = 1 - P(ham)} for the 3-class model. This is the scalar the calibrator maps
     * to a true frequency.
     */
    public double rawMalicious() {
        return clampUnit(spamScore + phishingScore);
    }

    /** A copy with the calibrated confidence replaced by an active calibrator's output. */
    public ModelScores withCalibratedConfidence(double calibratedConfidence) {
        return new ModelScores(spamScore, phishingScore, modelVersion, calibratedConfidence);
    }

    private static double clampUnit(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return v > 1.0 ? 1.0 : v;
    }
}
