package com.antispam.decision.routing;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes the LLM-routed fraction observable (story 05.01 AC 5). The whole point of routing is cost
 * control — escalate only the uncertain ~5% — so the fraction that escalates is the number that
 * proves the lever is bounded rather than runaway. This meter records, for every decision the
 * pipeline routes, whether it went to the LLM and (when it did) which predicate caused it.
 *
 * <p>It exposes two views:
 * <ul>
 *   <li>a counter {@code antispam.decision.routing} tagged {@code routed=true|false}, so the
 *       routed fraction is {@code routed / (routed + not)} — directly queryable;</li>
 *   <li>a counter {@code antispam.decision.routing.reason} tagged {@code reason}, incremented once
 *       per reason that fired, so the routed traffic is attributable to confidence vs. new-sender
 *       vs. boundary rather than seen only in aggregate.</li>
 * </ul>
 */
@Component
public class RoutingMeter {

    /** Counter name; tagged {@code routed=true|false}. Routed fraction = true / (true + false). */
    static final String ROUTING_COUNTER = "antispam.decision.routing";

    /** Counter name; tagged {@code reason} — one increment per {@link RoutingReason} that fired. */
    static final String REASON_COUNTER = "antispam.decision.routing.reason";

    private final MeterRegistry meters;

    @Autowired
    public RoutingMeter(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Records an escalation to the LLM and the reasons that drove it. Each reason is counted
     * separately because a single decision can fire more than one predicate, and attribution is
     * per-reason.
     */
    public void recordRouted(List<RoutingReason> reasons) {
        meters.counter(ROUTING_COUNTER, "routed", "true").increment();
        for (RoutingReason reason : reasons) {
            meters.counter(REASON_COUNTER, "reason", reason.name()).increment();
        }
    }

    /** Records a decision that stayed on the cheap fast path (not escalated). */
    public void recordFastPath() {
        meters.counter(ROUTING_COUNTER, "routed", "false").increment();
    }
}
