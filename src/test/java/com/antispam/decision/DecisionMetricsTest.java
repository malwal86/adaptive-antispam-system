package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The decision-pipeline meter in isolation (story 13.01). The system's load-bearing claims —
 * &lt;100ms fast path, the ~95/5 route mix, the 4-tier decision distribution — are only credible if
 * they are measured, so this meter records, for every persisted decision: the route it took, the
 * tier it reached, and how long it spent (as a percentile histogram so p95/p99 are aggregable across
 * instances). Degraded fail-degrades are counted separately.
 */
class DecisionMetricsTest {

    private static Classification decision(RouteUsed route, Decision tier, long latencyMs) {
        return new Classification(
                UUID.randomUUID(), UUID.randomUUID(), tier, List.of(), route, latencyMs,
                null, null, "policy-1", BigDecimal.ZERO, Instant.EPOCH);
    }

    @Test
    void each_route_increments_its_own_route_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionMetrics metrics = new DecisionMetrics(registry);

        metrics.record(decision(RouteUsed.HARD_RULE, Decision.BLOCK, 3));
        metrics.record(decision(RouteUsed.MODEL, Decision.ALLOW, 12));
        metrics.record(decision(RouteUsed.MODEL, Decision.WARN, 15));
        metrics.record(decision(RouteUsed.LLM, Decision.QUARANTINE, 8));

        assertThat(registry.get(DecisionMetrics.ROUTE).tag("route", "HARD_RULE").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get(DecisionMetrics.ROUTE).tag("route", "MODEL").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(DecisionMetrics.ROUTE).tag("route", "LLM").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void each_tier_increments_its_own_tier_counter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionMetrics metrics = new DecisionMetrics(registry);

        metrics.record(decision(RouteUsed.MODEL, Decision.ALLOW, 10));
        metrics.record(decision(RouteUsed.MODEL, Decision.ALLOW, 10));
        metrics.record(decision(RouteUsed.HARD_RULE, Decision.BLOCK, 2));

        assertThat(registry.get(DecisionMetrics.TIER).tag("tier", "ALLOW").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get(DecisionMetrics.TIER).tag("tier", "BLOCK").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    void latency_is_recorded_as_a_per_route_timer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionMetrics metrics = new DecisionMetrics(registry);

        metrics.record(decision(RouteUsed.MODEL, Decision.ALLOW, 10));
        metrics.record(decision(RouteUsed.MODEL, Decision.ALLOW, 30));

        Timer timer = registry.get(DecisionMetrics.LATENCY).tag("route", "MODEL").timer();
        assertThat(timer.count()).isEqualTo(2);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(40.0);
    }

    // The percentile-histogram contract (p95/p99 aggregability, AC 1) is verified end-to-end in
    // PrometheusScrapeIntegrationTest, which asserts the *_bucket series appear in a real scrape —
    // SimpleMeterRegistry does not render those buckets the way the Prometheus registry does.

    @Test
    void degraded_fail_degrades_are_counted() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DecisionMetrics metrics = new DecisionMetrics(registry);

        metrics.recordDegraded();
        metrics.recordDegraded();

        assertThat(registry.get(DecisionMetrics.DEGRADED).counter().count()).isEqualTo(2.0);
    }
}
