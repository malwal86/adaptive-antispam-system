package com.antispam.stream;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The controller translates the SSE {@code Last-Event-ID} header into a resume point and opens a
 * subscription on the stream. (Replay correctness itself is covered by {@link DecisionStreamTest}.)
 */
class DecisionStreamControllerTest {

    @Test
    void opens_a_subscription_from_the_oldest_buffered_event_when_no_header_present() {
        DecisionStream stream = new DecisionStream(8);
        DecisionStreamController controller = new DecisionStreamController(stream);

        SseEmitter emitter = controller.stream(null);

        assertThat(emitter).isNotNull();
        assertThat(stream.subscriberCount()).isEqualTo(1);
    }

    @Test
    void opens_a_subscription_when_resuming_from_a_last_event_id() {
        DecisionStream stream = new DecisionStream(8);
        DecisionStreamController controller = new DecisionStreamController(stream);

        SseEmitter emitter = controller.stream(7L);

        assertThat(emitter).isNotNull();
        assertThat(stream.subscriberCount()).isEqualTo(1);
    }
}
