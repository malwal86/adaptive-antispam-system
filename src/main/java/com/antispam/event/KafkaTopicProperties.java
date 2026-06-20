package com.antispam.event;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Event-spine configuration, bound from {@code app.kafka}.
 *
 * <p>Note this is distinct from {@code app.kafka-bootstrap-servers} (the broker
 * location, validated by {@code RequiredServicesProperties}); this section owns
 * the topic topology and the publish on/off switch.
 *
 * <p>{@link #enabled} lets the ingest path run without a broker — it is
 * {@code false} in tests and any environment where Kafka is not wired, so a
 * missing broker never turns into a hung ingest. When false, a no-op publisher
 * stands in (see {@code DisabledRawEmailPublisher}) so callers need no null check.
 *
 * @param enabled  whether ingest publishes to Kafka; defaults to {@code true}
 * @param rawTopic topology of the {@code emails.raw} topic
 */
@ConfigurationProperties(prefix = "app.kafka")
public record KafkaTopicProperties(Boolean enabled, RawTopic rawTopic) {

    public KafkaTopicProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        rawTopic = rawTopic == null ? RawTopic.defaults() : rawTopic;
    }

    /**
     * Topology of {@code emails.raw}.
     *
     * <p><b>Partitions</b> bound the consumer-group parallelism: the feature
     * extractor (story 02.02) runs one consumer per partition, and because the
     * topic is keyed by sender, each partition is one sender-shard processed in
     * order — hence the lock-free per-sender reputation updates. More partitions
     * = more parallelism but finer skew; 6 is a sensible demo default.
     *
     * <p><b>Replication</b> defaults to 3 (Aiven Developer tier has ≥3 brokers);
     * a single-broker local/test cluster must override this to 1.
     *
     * <p><b>Retention</b> is the spine's replay window: events older than this are
     * dropped from Kafka, but the immutable Postgres corpus remains the source of
     * truth and feeds the dedicated {@code emails.replay} topic (Epic 09).
     *
     * @param name              topic name
     * @param partitions        partition count
     * @param replicationFactor replicas per partition
     * @param retention         how long the broker keeps messages
     */
    public record RawTopic(
            String name,
            Integer partitions,
            Short replicationFactor,
            Duration retention) {

        public RawTopic {
            name = (name == null || name.isBlank()) ? "emails.raw" : name;
            partitions = partitions == null ? 6 : partitions;
            replicationFactor = replicationFactor == null ? (short) 3 : replicationFactor;
            retention = retention == null ? Duration.ofDays(7) : retention;
        }

        static RawTopic defaults() {
            return new RawTopic(null, null, null, null);
        }
    }
}
