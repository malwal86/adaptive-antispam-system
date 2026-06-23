package com.antispam.experiment.shadow.web;

import com.antispam.experiment.shadow.ShadowDecisionRepository.AgreementStats;

/**
 * The agreement evidence for an active-vs-shadow pairing, as returned by {@code GET /shadow/agreement}.
 * Adds the derived agreement rate to the raw counts so a client (the console, the promotion gate)
 * can read the headline number directly.
 *
 * @param activePolicyVersion the enforced regime
 * @param shadowPolicyVersion the candidate regime
 * @param total               decisions scored under both
 * @param agree               how many landed on the same tier
 * @param disagree            how many differed
 * @param shadowMoreSevere    disagreements where the shadow would escalate
 * @param shadowLessSevere    disagreements where the shadow would soften
 * @param agreementRate       {@code agree / total} in {@code [0,1]}, or {@code 0} when no data yet
 */
public record AgreementStatsResponse(
        String activePolicyVersion,
        String shadowPolicyVersion,
        long total,
        long agree,
        long disagree,
        long shadowMoreSevere,
        long shadowLessSevere,
        double agreementRate) {

    public static AgreementStatsResponse from(AgreementStats stats) {
        double rate = stats.total() == 0 ? 0.0 : (double) stats.agree() / stats.total();
        return new AgreementStatsResponse(
                stats.activePolicyVersion(), stats.shadowPolicyVersion(), stats.total(),
                stats.agree(), stats.disagree(), stats.shadowMoreSevere(), stats.shadowLessSevere(),
                rate);
    }
}
