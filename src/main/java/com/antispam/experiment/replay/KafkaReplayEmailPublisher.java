package com.antispam.experiment.replay;

import com.antispam.event.KafkaTopicProperties;
import com.antispam.event.ReplayEmailEvent;
import com.antispam.privacy.Redaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link ReplayEmailEvent}s to {@code emails.replay}, keyed by sender identity so a
 * sender's replayed mail lands on one partition — the same partitioning as {@code emails.raw}, so
 * replay exercises the real serialization and partition assignment rather than an in-memory
 * shortcut (story 09.01, PRD §Subsystem 8).
 *
 * <p>Sends are not awaited on the triggering thread: {@code KafkaTemplate.send} returns a future
 * whose outcome is logged from a callback. A delivery failure is surfaced at ERROR, never
 * swallowed — the corpus is durable in Postgres, so a failed replay is simply re-triggered.
 *
 * <p>Only active when the spine is enabled; when off, {@link DisabledReplayEmailPublisher} stands
 * in so the trigger path runs without a broker.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaReplayEmailPublisher implements ReplayEmailPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaReplayEmailPublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    @Autowired
    public KafkaReplayEmailPublisher(
            KafkaTemplate<String, Object> kafkaTemplate, KafkaTopicProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.replayTopic().name();
    }

    @Override
    public void publish(ReplayEmailEvent event) {
        kafkaTemplate.send(topic, event.senderKey(), event)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        log.error("failed to publish emails.replay event run={} id={} senderKey={}",
                                event.runId(), event.emailId(),
                                Redaction.maskAddress(event.senderKey()), failure);
                        return;
                    }
                    var metadata = result.getRecordMetadata();
                    log.debug("published emails.replay event run={} id={} partition={} offset={}",
                            event.runId(), event.emailId(), metadata.partition(), metadata.offset());
                });
    }
}
