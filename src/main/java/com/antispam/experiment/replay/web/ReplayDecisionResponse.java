package com.antispam.experiment.replay.web;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.routing.RoutingReason;
import com.antispam.experiment.replay.ReplayDecision;
import java.util.List;
import java.util.UUID;

/**
 * One replayed email's recorded verdict, as returned by {@code GET /replays/{runId}/decisions}.
 * Mirrors the shape of a live classification, but it is explicitly an experimental result tagged to
 * a replay run — never an enforced decision.
 *
 * @param emailId        the replayed email
 * @param decision       the tier the run's policy assigned
 * @param route          the route that produced it ({@code LLM} means it would have escalated)
 * @param reasonCodes    the codes justifying the verdict
 * @param routingReasons why it would escalate to the LLM; empty on the fast path
 * @param posterior      the fused P(abuse), or null when not fused
 * @param policyVersion  the policy this email was scored under
 */
public record ReplayDecisionResponse(
        UUID emailId,
        Decision decision,
        RouteUsed route,
        List<ReasonCode> reasonCodes,
        List<RoutingReason> routingReasons,
        Double posterior,
        String policyVersion) {

    public static ReplayDecisionResponse from(ReplayDecision decision) {
        return new ReplayDecisionResponse(
                decision.emailId(),
                decision.scored().decision(),
                decision.scored().route(),
                decision.scored().reasonCodes(),
                decision.scored().routingReasons(),
                decision.scored().posterior(),
                decision.scored().policyVersion());
    }
}
