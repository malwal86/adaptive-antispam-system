package com.antispam.reputation.accrual;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.event.SenderKey;
import com.antispam.ingest.IngestService;
import com.antispam.reputation.BetaReputation;
import com.antispam.reputation.ReputationService;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end proof of per-sender, lock-free reputation accrual over a real broker
 * and Postgres (story 03.05). A burst of distinct same-sender emails ingested
 * concurrently all key to one partition, so a single consumer thread applies them
 * in order: the append-only log ends with exactly one event per email (no lost
 * updates, AC 1/AC 2) and the materialized score equals the recompute-from-events
 * (no interleaving left the cache stale). The partition-skew gauge (AC 3) is
 * exposed throughout. Skips on machines without Docker; runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        // A single-broker test cluster cannot satisfy a replication factor of 3.
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=6"
})
class ReputationAccrualConsumerIntegrationTest extends AbstractPostgresIntegrationTest {

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
    private ReputationService reputationService;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_concurrent_same_sender_burst_accrues_exactly_once_per_email() throws Exception {
        int burst = 12;
        String sender = "burst@hot-sender.example";
        String senderKey = SenderKey.of(sender, "hot-sender.example");

        ingestConcurrently(sender, burst);

        awaitEventCount(senderKey, burst);

        // No lost updates: one authenticated GOOD event per email, no more, no fewer.
        assertThat(eventCount(senderKey)).isEqualTo(burst);

        // The recompute-from-events count matches the burst (decay over seconds is ~1.0),
        // and the materialized score tracks that recompute — serialization held, so the
        // cache reflects all N events, not a stale subset. The tolerance absorbs the tiny
        // read-time decay drift between the last write and this read (seconds against a
        // 7-day half-life); a genuinely stale cache, short several events, would differ by
        // orders of magnitude more.
        BetaReputation reputation = reputationService.currentReputation(senderKey);
        assertThat(reputation.count()).isCloseTo(burst, within(0.01));
        assertThat(materializedScore(senderKey)).isCloseTo(reputation.mean(), within(1e-3));
    }

    @Test
    void the_partition_skew_gauge_is_exposed() throws Exception {
        String sender = "skew-probe@sender.example";
        String senderKey = SenderKey.of(sender, "sender.example");

        ingestConcurrently(sender, 3);
        awaitEventCount(senderKey, 3);

        // AC 3: hot-sender skew is measurable. The gauge is registered and never below the
        // balanced floor of 1.0; per-partition processed counters sum to real accrual work.
        Double skew = meterRegistry.get("antispam.reputation.accrual.partition.skew").gauge().value();
        assertThat(skew).isGreaterThanOrEqualTo(1.0);
        double processed = meterRegistry.get("antispam.reputation.accrual.processed").counters()
                .stream().mapToDouble(c -> c.count()).sum();
        assertThat(processed).isGreaterThanOrEqualTo(3.0);
    }

    /** Ingests {@code count} distinct same-sender emails from {@code count} threads at once. */
    private void ingestConcurrently(String sender, int count) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            for (int i = 0; i < count; i++) {
                int n = i;
                pool.submit(() -> {
                    start.await();
                    ingestService.ingest(rawEmail(sender, n), "api");
                    return null;
                });
            }
            start.countDown(); // release all threads together to maximize contention
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitEventCount(String senderKey, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < deadline) {
            if (eventCount(senderKey) >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("only " + eventCount(senderKey) + " of " + expected
                + " reputation events accrued for " + senderKey + " within timeout");
    }

    private int eventCount(String senderKey) {
        return jdbc.queryForObject(
                "select count(*) from reputation_events where sender_key = ?", Integer.class, senderKey);
    }

    private double materializedScore(String senderKey) {
        return jdbc.queryForObject(
                "select current_reputation_score from senders where sender_key = ?", Double.class, senderKey);
    }

    /**
     * A distinct, authenticated (dmarc=pass) raw email from {@code sender}; {@code n}
     * keeps each message's content unique so it ingests as a new email (dedupe is by
     * content hash) yet keys to the same partition.
     */
    private static byte[] rawEmail(String sender, int n) {
        return ("From: " + sender + "\r\n"
                + "To: inbox@receiver.example\r\n"
                + "Subject: burst message " + n + "\r\n"
                + "Authentication-Results: mx; spf=pass; dkim=pass; dmarc=pass\r\n"
                + "Date: Wed, 13 Mar 2024 14:30:00 +0000\r\n"
                + "\r\n"
                + "Hello from message number " + n + ".\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
