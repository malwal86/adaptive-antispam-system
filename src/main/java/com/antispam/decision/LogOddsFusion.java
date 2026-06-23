package com.antispam.decision;

/**
 * Combines sender reputation with calibrated content evidence the principled way — in
 * log-odds, with the model's training base rate subtracted once (story 04.04, PRD
 * §Subsystem 2):
 *
 * <pre>{@code
 *   posterior_logit = logit(reputation_prior) + logit(P_model) − logit(π_train)
 *   posterior       = sigmoid(posterior_logit)
 * }</pre>
 *
 * <p><b>Why subtract {@code π_train}.</b> A calibrated content model already encodes
 * the base rate of abuse it was trained on; naively adding {@code logit(P_model)} to a
 * sender prior would count that base rate twice. Subtracting {@code logit(π_train)}
 * removes it, so the sender prior is counted exactly once. The identity falls out
 * cleanly: when {@code reputation_prior == π_train} the two terms cancel and the
 * posterior is exactly {@code P_model} — a sender sitting at the base rate shifts
 * nothing.
 *
 * <p><b>Conditional independence.</b> This is the naive-Bayes log-odds combination,
 * valid under the stated approximation that content ⊥ sender-history given the label.
 * The approximation is weakest for senders we know little about, which is exactly where
 * the Beta variance is largest — so {@link #fuse} propagates that variance into two routing
 * signals Epic 05 uses: the posterior-attenuated {@link FusedScore#uncertaintyBand() uncertainty
 * band} (for boundary proximity) and the content-independent {@link FusedScore#senderUncertainty()
 * sender uncertainty} = √variance (for the new-sender predicate, so an unseen sender escalates
 * however confident the content model is).
 *
 * <p>A pure, deterministic function with no I/O: the same inputs always yield the same
 * {@link FusedScore}, which is what lets fusion be unit-tested against hand-computed
 * numbers and audited.
 */
public final class LogOddsFusion {

    /**
     * Probabilities are clamped to {@code [EPS, 1−EPS]} before taking a logit so a
     * boundary input (0 or 1) yields a large-but-finite log-odds rather than ±∞. Small
     * enough not to perturb realistic calibrated scores.
     */
    private static final double EPS = 1e-12;

    private LogOddsFusion() {
    }

    /**
     * Fuses a sender's abuse prior with the model's calibrated abuse confidence.
     *
     * @param reputationPrior P(abuse) implied by the sender's reputation, in {@code [0,1]}
     *                        (i.e. {@code 1 − Beta mean}); higher means a worse sender
     * @param priorVariance   the Beta variance behind that prior; must be {@code >= 0}.
     *                        Larger ⇒ a wider uncertainty band
     * @param modelConfidence the calibrated P(abuse) from the content model, in {@code [0,1]}
     * @param trainingBaseRate the model's training base rate {@code π_train}, in {@code [0,1]}
     * @return the fused posterior and its uncertainty band
     * @throws IllegalArgumentException if any probability is outside {@code [0,1]} or NaN,
     *                                  or {@code priorVariance} is negative or NaN
     */
    public static FusedScore fuse(double reputationPrior, double priorVariance,
            double modelConfidence, double trainingBaseRate) {
        Probabilities.requireUnit("reputationPrior", reputationPrior);
        Probabilities.requireUnit("modelConfidence", modelConfidence);
        Probabilities.requireUnit("trainingBaseRate", trainingBaseRate);
        if (priorVariance < 0.0 || Double.isNaN(priorVariance)) {
            throw new IllegalArgumentException("priorVariance must be >= 0 but was " + priorVariance);
        }

        double posteriorLogit = logit(reputationPrior) + logit(modelConfidence) - logit(trainingBaseRate);
        double posterior = sigmoid(posteriorLogit);
        double band = uncertaintyBand(reputationPrior, posterior, priorVariance);
        // The sender's reputation standard deviation in probability space — content-independent,
        // unlike the posterior-attenuated band — so the new-sender routing predicate (05.01) fires
        // on an unseen sender regardless of how confident the content model is.
        double senderUncertainty = Math.sqrt(priorVariance);
        return new FusedScore(posterior, posteriorLogit, band, senderUncertainty);
    }

    /** The log-odds of {@code p}, clamped off the 0/1 boundary to stay finite. */
    static double logit(double p) {
        double c = clampUnit(p);
        return Math.log(c / (1.0 - c));
    }

    /** The logistic inverse of {@link #logit}. */
    static double sigmoid(double x) {
        return 1.0 / (1.0 + Math.exp(-x));
    }

    /**
     * Propagates the reputation prior's standard deviation through the fusion to a
     * probability-space half-width (the delta method): the prior enters the posterior
     * logit through {@code logit(reputationPrior)}, whose slope is {@code 1/(p(1−p))},
     * and the posterior logit maps back to probability with slope
     * {@code posterior(1−posterior)}. The band is therefore
     * {@code posterior(1−posterior) · √variance / (p(1−p))} — monotonic in the Beta
     * variance with everything else fixed, and zero when the sender carries no variance.
     */
    private static double uncertaintyBand(double reputationPrior, double posterior, double priorVariance) {
        if (priorVariance <= 0.0) {
            return 0.0;
        }
        double p = clampUnit(reputationPrior);
        double priorLogitSlope = 1.0 / (p * (1.0 - p));
        double sigmaPriorLogit = Math.sqrt(priorVariance) * priorLogitSlope;
        return posterior * (1.0 - posterior) * sigmaPriorLogit;
    }

    private static double clampUnit(double p) {
        if (p < EPS) {
            return EPS;
        }
        return p > 1.0 - EPS ? 1.0 - EPS : p;
    }
}
