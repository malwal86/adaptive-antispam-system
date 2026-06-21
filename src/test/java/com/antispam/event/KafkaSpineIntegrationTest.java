package com.antispam.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Proves the event spine end-to-end against a real broker: ingesting an email
 * publishes exactly one message to {@code emails.raw}, keyed by sender identity,
 * and two emails from the same sender co-locate on one partition (the property
 * that later gives lock-free per-sender reputation). Postgres comes from the
 * shared base; a Kafka broker is added here and the spine is re-enabled (it is
 * off in the default test profile).
 *
 * <p>Like the other Testcontainers tests, this skips on machines without a
 * running Docker daemon and runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        // A single-broker test cluster cannot satisfy a replication factor of 3.
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=3"
})
class KafkaSpineIntegrationTest extends AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            KAFKA.start();
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ObjectMapper objectMapper;

    private Consumer<String, byte[]> consumer;

    @BeforeEach
    void subscribe() {
        var props = KafkaTestUtils.consumerProps(KAFKA.getBootstrapServers(), "spine-it", "true");
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer = new DefaultKafkaConsumerFactory<>(
                props, new StringDeserializer(), new ByteArrayDeserializer()).createConsumer();
        // Explicitly assign every partition and seek to the start rather than subscribe():
        // assignment skips consumer-group join/rebalance entirely, which removes the only
        // timing race in this verification consumer. That matters because the broker is
        // shared with the feature and reputation-accrual consumer groups (each replaying
        // the backlog at startup), and a rebalance under that load could outlast the poll
        // window. With assign + seekToBeginning the consumer reads deterministically from
        // offset 0 of each partition, so a published record is always found.
        List<TopicPartition> partitions = consumer.partitionsFor("emails.raw").stream()
                .map(info -> new TopicPartition(info.topic(), info.partition()))
                .toList();
        consumer.assign(partitions);
        consumer.seekToBeginning(partitions);
    }

    @AfterEach
    void unsubscribe() {
        if (consumer != null) {
            consumer.close();
        }
    }

    @Test
    void ingesting_an_email_publishes_one_keyed_event_carrying_the_canonical_id() throws Exception {
        IngestResult result = ingestService.ingest(
                emailFrom("alice@example.com", "first"), "api");

        ConsumerRecord<String, byte[]> record = awaitRecordFor(result);

        assertThat(record.key()).isEqualTo("alice@example.com");
        RawEmailEvent event = objectMapper.readValue(record.value(), RawEmailEvent.class);
        assertThat(event.emailId()).isEqualTo(result.emailId());
        assertThat(event.senderKey()).isEqualTo("alice@example.com");
        assertThat(event.contentHashHex()).isEqualTo(result.contentHashHex());
        assertThat(event.schemaVersion()).isEqualTo(RawEmailEvent.CURRENT_SCHEMA_VERSION);
    }

    @Test
    void two_emails_from_the_same_sender_land_on_the_same_partition() throws Exception {
        IngestResult first = ingestService.ingest(emailFrom("bob@example.com", "alpha"), "api");
        IngestResult second = ingestService.ingest(emailFrom("bob@example.com", "beta"), "api");

        int firstPartition = awaitRecordFor(first).partition();
        int secondPartition = awaitRecordFor(second).partition();

        assertThat(secondPartition).isEqualTo(firstPartition);
    }

    /** A minimal raw email; the body varies so each is a distinct (non-duplicate) ingest. */
    private static byte[] emailFrom(String sender, String body) {
        return ("From: " + sender + "\nSubject: test\n\n" + body + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Polls {@code emails.raw} until the event for {@code result} appears (matched
     * by canonical id), or fails after a bounded wait. Reading by id keeps the
     * test robust to other records already on the topic.
     */
    private ConsumerRecord<String, byte[]> awaitRecordFor(IngestResult result) throws Exception {
        List<ConsumerRecord<String, byte[]>> seen = new ArrayList<>();
        // Re-seek to offset 0 of every assigned partition before each search. poll()
        // advances the position past the whole returned batch, not just the records we
        // looked at — so when two same-key records (same partition) arrive in one batch, a
        // search that returns on the first would leave the second already consumed past.
        // Re-seeking makes each lookup an independent full scan, robust to batch boundaries
        // and to records left over from a previous test on this shared container.
        consumer.seekToBeginning(consumer.assignment());
        // The consumer is pre-assigned (no group rebalance), so the record is on the topic
        // and only the async after-commit publish stands between ingest and visibility. A
        // generous deadline still absorbs broker load from the feature and reputation-
        // accrual consumer groups sharing this container.
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, byte[]> batch = consumer.poll(Duration.ofMillis(500));
            for (ConsumerRecord<String, byte[]> record : batch) {
                RawEmailEvent event = objectMapper.readValue(record.value(), RawEmailEvent.class);
                if (event.emailId().equals(result.emailId())) {
                    return record;
                }
                seen.add(record);
            }
        }
        throw new AssertionError("no emails.raw record for id " + result.emailId()
                + " within timeout; saw " + seen.size() + " other records");
    }
}
