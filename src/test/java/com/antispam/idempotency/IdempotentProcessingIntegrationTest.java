package com.antispam.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.features.EmailFeaturesService;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of idempotent at-least-once processing (story 02.03) against a
 * real Postgres. Feature extraction is driven directly through the service —
 * re-invoking {@code extractAndStore} is exactly what a Kafka retry, rebalance, or
 * replay does to the consumer — so the test needs no broker. It pins the three
 * properties the story promises: a redelivery leaves state unchanged, the duplicate
 * is observable via a metric, and an out-of-order batch converges to the
 * single-delivery state.
 */
class IdempotentProcessingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String GROUP = "feature-extractor";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EmailFeaturesService featuresService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    @Test
    void redelivering_the_same_email_leaves_state_unchanged_and_counts_the_duplicate() {
        UUID emailId = ingest("idempotent single-delivery case");
        double duplicatesBefore = duplicateCount();

        // First delivery does the work; the second is a redelivery (retry/rebalance).
        featuresService.extractAndStore(emailId);
        featuresService.extractAndStore(emailId);

        assertThat(featureRowCount(emailId)).isEqualTo(1);
        assertThat(ledgerRowCount(emailId)).isEqualTo(1);
        assertThat(duplicateCount() - duplicatesBefore).isEqualTo(1.0);
    }

    @Test
    void out_of_order_redelivery_of_a_batch_converges_to_single_delivery_state() {
        // A fixed batch, each email delivered three times, then shuffled with a fixed
        // seed: a deterministic stand-in for retries arriving interleaved and reordered.
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(ingest("idempotent batch case " + i));
        }
        List<UUID> deliveries = new ArrayList<>();
        for (int copy = 0; copy < 3; copy++) {
            deliveries.addAll(ids);
        }
        Collections.shuffle(deliveries, new Random(42));
        double duplicatesBefore = duplicateCount();

        deliveries.forEach(featuresService::extractAndStore);

        // Convergent: one row per email regardless of order or repeat count.
        for (UUID id : ids) {
            assertThat(featureRowCount(id)).as("feature rows for %s", id).isEqualTo(1);
            assertThat(ledgerRowCount(id)).as("ledger rows for %s", id).isEqualTo(1);
        }
        // Every delivery beyond the first per email is a counted duplicate.
        int expectedDuplicates = deliveries.size() - ids.size();
        assertThat(duplicateCount() - duplicatesBefore).isEqualTo(expectedDuplicates);
    }

    private UUID ingest(String marker) {
        IngestResult result = ingestService.ingest(rawEmail(marker), "api");
        return result.emailId();
    }

    private int featureRowCount(UUID emailId) {
        return jdbc.queryForObject(
                "select count(*) from email_features where email_id = ? and feature_version = ?",
                Integer.class, emailId, EmailFeatureExtractor.FEATURE_VERSION);
    }

    private int ledgerRowCount(UUID emailId) {
        return jdbc.queryForObject(
                "select count(*) from processed_messages where consumer_group = ? and message_key = ?",
                Integer.class, GROUP, emailId.toString());
    }

    private double duplicateCount() {
        Counter counter = meters.find(ProcessedMessageLedger.DUPLICATE_COUNTER)
                .tag("consumer.group", GROUP)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }

    /** A minimal, unique raw email; {@code marker} keeps each ingest distinct (emails dedupe by content hash). */
    private static byte[] rawEmail(String marker) {
        return ("From: alice@idem-test.example\r\n"
                + "To: bob@idem-test.example\r\n"
                + "Subject: Hello " + marker + "\r\n"
                + "Date: Wed, 13 Mar 2024 14:30:00 +0000\r\n"
                + "\r\n"
                + "Visit https://idem-test.example/welcome today! (" + marker + ")\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }
}
