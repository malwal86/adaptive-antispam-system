package com.antispam.decision;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Makes the decision pipeline's load-bearing claims measurable (story 13.01). The PRD's headline
 * numbers — the &lt;100ms fast path, the ~95/5 fast-vs-LLM route mix, the four-tier decision
 * distribution — are only credible as facts, not assertions, if they are recorded for every
 * decision and exported to Prometheus. This meter does exactly that, per persisted decision:
 *
 * <ul>
 *   <li>a {@link Timer} {@code antispam.decision.latency} tagged {@code route}, with a percentile
 *       histogram so p95/p99 are aggregable across instances in Prometheus — filtering to the
 *       non-LLM routes gives the fast-path budget;</li>
 *   <li>a counter {@code antispam.decision.route} tagged {@code route} — the full {@code route_used}
 *       mix across {@link RouteUsed#HARD_RULE}, {@link RouteUsed#MODEL}, and {@link RouteUsed#LLM};</li>
 *   <li>a counter {@code antispam.decision.tier} tagged {@code tier} — decisions by verdict tier
 *       (allow/warn/quarantine/block);</li>
 *   <li>a counter {@code antispam.decision.degraded} — synchronous fail-degrades (LLM routed but
 *       unavailable/disabled), the degraded-mode signal on the inline path.</li>
 * </ul>
 *
 * <p>This is complementary to, not a duplicate of, {@link com.antispam.decision.routing.RoutingMeter}:
 * that meter records the routing <em>decision</em> (the escalate-or-not fraction and why), whereas
 * this records the <em>final route</em> every persisted decision actually took. The async
 * quarantine-pending degrade path is counted by {@link com.antispam.decision.llm.LlmMeter} (resolution
 * state and SLA timeout); this meter's degraded counter covers only the synchronous inline degrade.
 */
@Component
public class DecisionMetrics {

    /** Timer name; tagged {@code route} — per-route decision latency with a percentile histogram. */
    static final String LATENCY = "antispam.decision.latency";

    /** Counter name; tagged {@code route} — the final {@code route_used} of every decision. */
    static final String ROUTE = "antispam.decision.route";

    /** Counter name; tagged {@code tier} — decisions by verdict tier (allow/warn/quarantine/block). */
    static final String TIER = "antispam.decision.tier";

    /** Counter name — synchronous fail-degrades (LLM routed but unavailable/disabled). */
    static final String DEGRADED = "antispam.decision.degraded";

    private final MeterRegistry meters;

    @Autowired
    public DecisionMetrics(MeterRegistry meters) {
        this.meters = meters;
    }

    /**
     * Records the route, tier, and latency of one persisted decision. The latency timer carries a
     * percentile histogram so the &lt;100ms fast-path budget is queryable as p95/p99 (filter to the
     * non-LLM routes); the route and tier counters give the route mix and decision distribution.
     */
    public void record(Classification classification) {
        String route = classification.route().name();
        meters.counter(ROUTE, "route", route).increment();
        meters.counter(TIER, "tier", classification.decision().name()).increment();
        Timer.builder(LATENCY)
                .tag("route", route)
                .description("Per-route decision latency; filter to non-LLM routes for the fast-path budget")
                .publishPercentileHistogram()
                .register(meters)
                .record(classification.latencyMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Records one synchronous fail-degrade: the decision was routed to the LLM but the provider was
     * unavailable or disabled, so it fell back to the fast-path posterior (story 05.02).
     */
    public void recordDegraded() {
        meters.counter(DEGRADED).increment();
    }
}
