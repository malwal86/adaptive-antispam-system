package com.antispam.stream;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The decision stream's transport guarantees (story 12.01): monotonic sequence ids, gap-free
 * replay strictly after a {@code Last-Event-ID}, a bounded buffer that evicts oldest-first, and a
 * payload identical to the analyzer card's.
 */
class DecisionStreamTest {

    private static Classification blockDecision() {
        return new Classification(UUID.randomUUID(), UUID.randomUUID(), Decision.BLOCK,
                List.of(ReasonCode.KNOWN_BAD_URL), RouteUsed.HARD_RULE, 2L, null, null,
                "bootstrap-v1", null, Instant.parse("2026-06-05T12:00:00Z"));
    }

    private static DecisionMadeEvent anEvent() {
        return new DecisionMadeEvent(blockDecision());
    }

    @Test
    void assigns_strictly_increasing_sequence_ids_starting_at_one() {
        DecisionStream stream = new DecisionStream(8);

        stream.onDecision(anEvent());
        stream.onDecision(anEvent());

        assertThat(stream.lastEventId()).isEqualTo(2L);
        assertThat(stream.bufferedSince(0)).extracting(DecisionStream.Sequenced::seq)
                .containsExactly(1L, 2L);
    }

    @Test
    void replays_only_events_strictly_after_the_last_event_id() {
        DecisionStream stream = new DecisionStream(8);
        stream.onDecision(anEvent());
        stream.onDecision(anEvent());
        stream.onDecision(anEvent());

        assertThat(stream.bufferedSince(1)).extracting(DecisionStream.Sequenced::seq)
                .containsExactly(2L, 3L);
        assertThat(stream.bufferedSince(3)).isEmpty();
    }

    @Test
    void bounds_the_replay_buffer_evicting_oldest_first() {
        DecisionStream stream = new DecisionStream(2);

        for (int i = 0; i < 5; i++) {
            stream.onDecision(anEvent());
        }

        // Capacity 2 → only the two most recent survive; the sequence counter keeps climbing.
        assertThat(stream.bufferedSince(0)).extracting(DecisionStream.Sequenced::seq)
                .containsExactly(4L, 5L);
        assertThat(stream.lastEventId()).isEqualTo(5L);
    }

    @Test
    void projects_the_decision_onto_the_analyzer_payload() {
        DecisionStream stream = new DecisionStream(8);
        Classification decision = blockDecision();

        stream.onDecision(new DecisionMadeEvent(decision));

        AnalyzeResponse payload = stream.bufferedSince(0).get(0).decision();
        assertThat(payload.classificationId()).isEqualTo(decision.id());
        assertThat(payload.emailId()).isEqualTo(decision.emailId());
        assertThat(payload.tier()).isEqualTo("block");
        assertThat(payload.routeUsed()).isEqualTo("hard_rule");
        assertThat(payload.reasonCodes()).containsExactly("KNOWN_BAD_URL");
        assertThat(payload.duplicate()).isFalse();
    }

    @Test
    void subscribe_registers_a_live_emitter() {
        DecisionStream stream = new DecisionStream(8);

        SseEmitter emitter = stream.subscribe(0);

        assertThat(emitter).isNotNull();
        assertThat(stream.subscriberCount()).isEqualTo(1);
    }
}
