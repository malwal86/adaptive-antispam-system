package com.antispam.reputation.web;

import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.GatedReputation;

/**
 * API view of a sender's soft auth-gated reputation (story 03.03): the two reputations
 * an email can be assigned depending on whether it proved DMARC alignment. Returning
 * both — not a single score — is the point: an operator (and the console, Epic 12) can
 * see that a warmed-up domain's earned trust lives in {@code authenticated}, while
 * unauthenticated (possibly spoofed) mail only ever sees the neutral-capped
 * {@code unauthenticated} view and so inherits nothing.
 *
 * <p>Each {@link View} carries the Beta point estimate, its uncertainty, and the
 * evidence behind it. The unauthenticated view's {@code good} is the cap-adjusted
 * value, so its {@code mean} never exceeds neutral. All fields are non-PII aggregates.
 *
 * @param senderKey       the sender these numbers describe
 * @param authenticated   full reputation earned from DMARC-aligned mail
 * @param unauthenticated neutral-capped reputation from unauthenticated mail
 */
public record GatedReputationResponse(
        String senderKey,
        View authenticated,
        View unauthenticated) {

    /**
     * One bucket's Beta view.
     *
     * @param mean     posterior mean trust estimate in {@code (0, 1)}
     * @param variance posterior variance (uncertainty); large for thin evidence
     * @param good     weighted count of good signals (cap-adjusted in the unauth view)
     * @param bad      weighted count of bad signals
     * @param n        observed evidence count ({@code good + bad}), excluding the prior
     */
    public record View(double mean, double variance, double good, double bad, double n) {
        public static View from(BetaReputation reputation) {
            return new View(
                    reputation.mean(),
                    reputation.variance(),
                    reputation.good(),
                    reputation.bad(),
                    reputation.count());
        }
    }

    public static GatedReputationResponse from(String senderKey, GatedReputation reputation) {
        return new GatedReputationResponse(
                senderKey,
                View.from(reputation.authenticated()),
                View.from(reputation.unauthenticated()));
    }
}
