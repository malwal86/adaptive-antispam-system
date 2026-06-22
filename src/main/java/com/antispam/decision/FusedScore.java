package com.antispam.decision;

/**
 * The Bayesian fusion of sender reputation with calibrated content evidence (story
 * 04.04): the combined posterior probability that a mail is abuse, plus the
 * uncertainty band that downstream LLM routing (Epic 05) escalates on.
 *
 * <p>The {@link #posterior()} is computed in log-odds space with the model's training
 * base rate subtracted once, so the sender prior is counted exactly once (PRD
 * §Subsystem 2); see {@link LogOddsFusion} for the formula. The
 * {@link #uncertaintyBand()} is a probability-space half-width propagated from the
 * sender's Beta variance — wide for a new/uncertain sender, narrow for a
 * well-established one — which is precisely where the conditional-independence
 * approximation behind the fusion is weakest, so it is the natural "route this to the
 * LLM" signal.
 *
 * @param posterior      fused P(abuse) in {@code [0,1]}
 * @param posteriorLogit the posterior in log-odds space (the quantity actually summed);
 *                       kept for auditability and for routing predicates that work in
 *                       logit space
 * @param uncertaintyBand non-negative half-width around the posterior attributable to
 *                       reputation uncertainty; {@code 0} when the sender carries no
 *                       variance
 */
public record FusedScore(double posterior, double posteriorLogit, double uncertaintyBand) {

    public FusedScore {
        if (posterior < 0.0 || posterior > 1.0 || Double.isNaN(posterior)) {
            throw new IllegalArgumentException("posterior must be in [0,1] but was " + posterior);
        }
        if (uncertaintyBand < 0.0 || Double.isNaN(uncertaintyBand)) {
            throw new IllegalArgumentException(
                    "uncertaintyBand must be non-negative but was " + uncertaintyBand);
        }
        if (!Double.isFinite(posteriorLogit)) {
            throw new IllegalArgumentException("posteriorLogit must be finite but was " + posteriorLogit);
        }
    }
}
