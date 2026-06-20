package com.antispam.event;

/**
 * Publishes a {@link RawEmailEvent} onto the event spine after the email has been
 * durably persisted. Implementations are responsible for keying by sender so that
 * per-sender messages co-locate on one partition.
 *
 * <p>There is always exactly one bean: the Kafka-backed publisher when the spine
 * is enabled, a no-op stand-in otherwise (see {@link KafkaTopicProperties#enabled()}).
 * Callers therefore never branch on whether Kafka is configured.
 */
public interface RawEmailPublisher {

    /**
     * Publishes the event for a newly persisted email. Must not throw on a
     * transient broker problem — delivery is retried/surfaced by the
     * implementation, not propagated into the ingest call.
     */
    void publish(RawEmailEvent event);
}
