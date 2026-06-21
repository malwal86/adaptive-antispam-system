package com.antispam.reputation.web;

import com.antispam.reputation.BetaReputation;

/**
 * API view of a sender's Beta reputation (story 03.01): the point estimate
 * ({@code mean}), its uncertainty ({@code variance}), and the evidence behind them
 * ({@code good}, {@code bad}, {@code n}). Returning the variance and counts — not
 * just a single score — is the point: a client can see that a new sender's 0.5 is a
 * wide guess, not a confident neutral. All fields are non-PII aggregates; the
 * sender key is an identity the caller already supplied.
 *
 * @param senderKey the sender these numbers describe
 * @param mean      posterior mean trust estimate in {@code (0, 1)}
 * @param variance  posterior variance (uncertainty); large for new senders
 * @param good      weighted count of good signals
 * @param bad       weighted count of bad signals
 * @param n         observed evidence count ({@code good + bad}), excluding the prior
 */
public record ReputationResponse(
        String senderKey,
        double mean,
        double variance,
        double good,
        double bad,
        double n) {

    public static ReputationResponse from(String senderKey, BetaReputation reputation) {
        return new ReputationResponse(
                senderKey,
                reputation.mean(),
                reputation.variance(),
                reputation.good(),
                reputation.bad(),
                reputation.count());
    }
}
