package com.antispam.experiment.shadow.web;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.experiment.shadow.ShadowDecision;
import com.antispam.experiment.shadow.ShadowDiff.Agreement;
import com.antispam.experiment.shadow.ShadowDiff.Direction;
import java.util.UUID;

/**
 * One live email's active-vs-shadow diff, as returned by {@code GET /shadow/decisions/email/{id}}.
 *
 * @param emailId             the live email
 * @param activePolicyVersion the enforced regime
 * @param activeDecision      the verdict it enforced
 * @param activeRoute         the route it took
 * @param activePosterior     the fused P(abuse) under the active policy, or null
 * @param shadowPolicyVersion the candidate regime
 * @param shadowDecision      the verdict it would have assigned (logged-only)
 * @param shadowRoute         the route it would have taken
 * @param shadowPosterior     the fused P(abuse) under the shadow policy, or null
 * @param agreement           whether the two verdicts matched
 * @param direction           which way the shadow would move the verdict
 */
public record ShadowDecisionResponse(
        UUID emailId,
        String activePolicyVersion,
        Decision activeDecision,
        RouteUsed activeRoute,
        Double activePosterior,
        String shadowPolicyVersion,
        Decision shadowDecision,
        RouteUsed shadowRoute,
        Double shadowPosterior,
        Agreement agreement,
        Direction direction) {

    public static ShadowDecisionResponse from(ShadowDecision d) {
        return new ShadowDecisionResponse(
                d.emailId(),
                d.active().policyVersion(), d.active().decision(), d.active().route(), d.active().posterior(),
                d.shadow().policyVersion(), d.shadow().decision(), d.shadow().route(), d.shadow().posterior(),
                d.diff().agreement(), d.diff().direction());
    }
}
