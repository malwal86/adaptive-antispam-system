package com.antispam.reputation;

/**
 * A sender's reputation under soft auth-gating (story 03.03): the two views an email
 * can be assigned depending on whether it proved DMARC alignment. Holding both — and
 * letting the caller pick by the reading email's own auth status via
 * {@link #forAuthStatus} — is what stops a spoofer from inheriting a warmed-up
 * domain's trust.
 *
 * <ul>
 *   <li>{@link #authenticated()} — the full, uncapped Beta over the sender's
 *       authenticated bucket. This is the reputation a legitimate, DMARC-aligned
 *       sender has earned; it is deliberately <b>isolated</b> from unauthenticated
 *       traffic so spoofed mail can neither lift it nor drag it down (poisoning in
 *       either direction is denied).</li>
 *   <li>{@link #unauthenticated()} — the Beta over the unauthenticated bucket with its
 *       trust <b>capped at neutral</b>: it can sit at or below the prior mean but never
 *       above it. So unauthenticated (possibly spoofed) mail can lower a sender's
 *       standing yet is "neutral, never trusted."</li>
 * </ul>
 *
 * <p><b>How the cap stays a proper Beta.</b> Rather than clipping a computed mean to
 * neutral (which would throw away the variance the downstream Bayesian fusion needs),
 * the cap limits the bucket's good evidence to {@code (alpha/beta)·bad} — the exact
 * point where the Beta posterior mean equals the prior mean {@code alpha/(alpha+beta)}.
 * Below that point the view is the genuine sub-neutral Beta; at or above it the view is
 * the neutral prior-mean Beta. "Neutral" therefore tracks the configured prior, not a
 * hard-coded 0.5, so the cap holds under any prior.
 *
 * @param authenticated   full reputation from DMARC-aligned mail
 * @param unauthenticated neutral-capped reputation from unauthenticated mail
 */
public record GatedReputation(BetaReputation authenticated, BetaReputation unauthenticated) {

    /**
     * Builds both views from a sender's per-bucket evidence and the Beta prior. The
     * authenticated view is the raw authenticated bucket; the unauthenticated view caps
     * its good evidence at neutral (see the class comment).
     */
    public static GatedReputation from(BucketedReputationCounts counts, ReputationProperties priors) {
        double alpha = priors.alpha();
        double beta = priors.beta();

        ReputationCounts auth = counts.authenticated();
        BetaReputation authenticated = new BetaReputation(auth.good(), auth.bad(), alpha, beta);

        // Cap good at the level where the posterior mean equals the prior (neutral)
        // mean: (good+alpha)/(good+bad+alpha+beta) <= alpha/(alpha+beta) iff
        // good <= (alpha/beta)*bad. Excess good is discarded for trust; bad is uncapped.
        ReputationCounts unauth = counts.unauthenticated();
        double cappedGood = Math.min(unauth.good(), (alpha / beta) * unauth.bad());
        BetaReputation unauthenticated = new BetaReputation(cappedGood, unauth.bad(), alpha, beta);

        return new GatedReputation(authenticated, unauthenticated);
    }

    /**
     * The view an email with the given alignment is entitled to: the full
     * {@link #authenticated()} reputation when DMARC-aligned, otherwise the
     * neutral-capped {@link #unauthenticated()} view.
     */
    public BetaReputation forAuthStatus(boolean dmarcAligned) {
        return dmarcAligned ? authenticated : unauthenticated;
    }
}
