package com.antispam.event;

import com.antispam.privacy.Redaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes {@link RawEmailEvent}s to {@code emails.raw}, keyed by sender identity
 * so all of a sender's mail lands on one partition (PRD §Subsystem 3).
 *
 * <p><b>Delivery guarantee.</b> The send happens <i>after</i> the email is
 * committed to Postgres (the publish call sits at the end of {@code IngestService}).
 * The producer is configured for durability — {@code acks=all} plus the idempotent
 * producer (see {@code application.yml}) — and Kafka retries transient broker
 * errors internally, so a hiccup does not drop the event. A failure that survives
 * those retries is <b>surfaced</b> (logged at ERROR with the email id), never
 * swallowed. This is the documented "transactional-after-commit" pattern; its one
 * gap is a crash in the window between the DB commit and a successful send, which a
 * later transactional outbox closes if exactly-once-into-Kafka is ever required.
 *
 * <p>The send is not awaited on the ingest thread: {@code KafkaTemplate.send}
 * returns a future and the outcome is logged from its callback, so a healthy
 * broker adds no measurable latency to ingest and a slow one cannot stall it
 * beyond the producer's {@code max.block.ms}.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class KafkaRawEmailPublisher implements RawEmailPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaRawEmailPublisher.class);

    private final KafkaTemplate<String, RawEmailEvent> kafkaTemplate;
    private final String topic;

    @Autowired
    public KafkaRawEmailPublisher(
            KafkaTemplate<String, RawEmailEvent> kafkaTemplate,
            KafkaTopicProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = properties.rawTopic().name();
    }

    @Override
    public void publish(RawEmailEvent event) {
        kafkaTemplate.send(topic, event.senderKey(), event)
                .whenComplete((result, failure) -> {
                    if (failure != null) {
                        // Surfaced, not swallowed: the email is already durably
                        // stored, so this is recoverable by replaying from Postgres.
                        log.error("failed to publish emails.raw event for id={} senderKey={}",
                                event.emailId(), Redaction.maskAddress(event.senderKey()), failure);
                        return;
                    }
                    var metadata = result.getRecordMetadata();
                    log.info("published emails.raw event id={} senderKey={} partition={} offset={}",
                            event.emailId(), Redaction.maskAddress(event.senderKey()),
                            metadata.partition(), metadata.offset());
                });
    }
}
