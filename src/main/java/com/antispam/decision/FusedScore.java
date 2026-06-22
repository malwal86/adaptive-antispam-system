package com.antispam.decision;

/**
 * The Bayesian fusion of sender reputation with calibrated content evidence (story
 * 04.04): the combined posterior probability that a mail is abuse, plus the
 * uncertainty band that downstream LLM routing (Epic 05) escalates on.
 *
 * <p>The {@link #posterior()} is computed in log-odds space with the model's training
 * base rate subtracted once, so the sender prior is counted exactly once (PRD
 * §Subsystem 2); see {@link LogOddsFusion} for the formula.
 *
 * <p>The score carries <em>two distinct uncertainty signals</em>, because LLM routing (Epic 05)
 * needs both and they behave differently:
 * <ul>
 *   <li>{@link #uncertaintyBand()} — a probability-space half-width <em>around the posterior</em>,
 *       so it is attenuated toward zero as the decision becomes confident. It widens the
 *       boundary-proximity routing band: a confident posterior far from a cut-point needs no
 *       boundary escalation.</li>
 *   <li>{@link #senderUncertainty()} — the sender's reputation standard deviation (√Beta-variance),
 *       which is <em>content-independent</em>: it stays wide for an unseen/volatile sender no matter
 *       how confident the content model is. It drives the new-sender routing predicate, so an
 *       uncertain sender escalates on its own — the behaviour the PRD intends ("unseen senders route
 *       to the LLM"). Using the attenuated band here instead would wrongly let a confident verdict on
 *       a brand-new sender skip the LLM.</li>
 * </ul>
 *
 * @param posterior         fused P(abuse) in {@code [0,1]}
 * @param posteriorLogit    the posterior in log-odds space (the quantity actually summed); kept for
 *                          auditability and for routing predicates that work in logit space
 * @param uncertaintyBand   non-negative half-width around the posterior attributable to reputation
 *                          uncertainty, attenuated by the posterior; {@code 0} when the sender carries
 *                          no variance. Used by the boundary-proximity routing predicate.
 * @param senderUncertainty the sender's reputation standard deviation (√Beta-variance), content-
 *                          independent and non-negative; {@code 0} when the sender carries no variance.
 *                          Used by the new-sender routing predicate.
 */
public record FusedScore(double posterior, double posteriorLogit, double uncertaintyBand,
        double senderUncertainty) {

    public FusedScore {
        if (posterior < 0.0 || posterior > 1.0 || Double.isNaN(posterior)) {
            throw new IllegalArgumentException("posterior must be in [0,1] but was " + posterior);
        }
        if (uncertaintyBand < 0.0 || Double.isNaN(uncertaintyBand)) {
            throw new IllegalArgumentException(
                    "uncertaintyBand must be non-negative but was " + uncertaintyBand);
        }
        if (senderUncertainty < 0.0 || Double.isNaN(senderUncertainty)) {
            throw new IllegalArgumentException(
                    "senderUncertainty must be non-negative but was " + senderUncertainty);
        }
        if (!Double.isFinite(posteriorLogit)) {
            throw new IllegalArgumentException("posteriorLogit must be finite but was " + posteriorLogit);
        }
    }
}
