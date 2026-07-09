package com.antispam.stream;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.analyze.AnalyzeResponse;
import com.antispam.decision.Classification;
import com.antispam.decision.Decision;
import com.antispam.decision.DecisionMadeEvent;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

/**
 * Proves the live-stream wiring inside the real Spring context: a {@link DecisionMadeEvent}
 * published on the application event bus (as {@code DecisionService} does after it persists a
 * decision) is delivered to the {@link DecisionStream} listener and buffered for replay. This is
 * the seam unit tests can't cover — that the {@code @EventListener}/{@code @Component} are actually
 * registered. The decision pipeline emitting the event end-to-end is covered by the analyzer tests;
 * the browser receiving it over SSE is covered by the console's Playwright e2e.
 */
class DecisionStreamWiringIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ApplicationEventPublisher events;

    @Autowired
    private DecisionStream stream;

    @Test
    void a_published_decision_event_is_buffered_by_the_stream_for_replay() {
        long before = stream.lastEventId();
        Classification decision = new Classification(
                UUID.randomUUID(), UUID.randomUUID(), Decision.BLOCK,
                List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 2L, null, null,
                "bootstrap-v1", null, Instant.parse("2026-06-05T12:00:00Z"));

        events.publishEvent(new DecisionMadeEvent(decision));

        assertThat(stream.lastEventId()).isEqualTo(before + 1);
        List<DecisionStream.Sequenced> replay = stream.bufferedSince(before);
        assertThat(replay).extracting(seq -> seq.decision().verdict().classificationId())
                .contains(decision.id());
        AnalyzeResponse payload = replay.get(replay.size() - 1).decision().verdict();
        assertThat(payload.tier()).isEqualTo("block");
        assertThat(payload.routeUsed()).isEqualTo("hard_rule");
    }
}
