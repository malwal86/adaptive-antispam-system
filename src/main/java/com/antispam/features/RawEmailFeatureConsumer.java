package com.antispam.features;

import com.antispam.event.RawEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code emails.raw} and extracts features for each email onto the
 * versioned {@code email_features} table (story 02.02). The topic is keyed by
 * sender, so each partition is one sender-shard processed in order — the same
 * property that later enables lock-free per-sender reputation updates.
 *
 * <p>Only active when the spine is enabled ({@code app.kafka.enabled=true}); when
 * off — the default in tests and any environment without a broker — the listener
 * bean is not created, so no consumer connects.
 *
 * <p><b>Partition safety.</b> Extraction is total (a malformed email degrades to
 * sentinels, never throws) and the store is an idempotent upsert, so normal
 * processing cannot poison a partition. As a backstop, any unexpected error while
 * handling one record is caught and logged so the offset still advances and a
 * single bad record cannot wedge the shard. The email is durable in Postgres, so
 * a dropped record is recoverable by replay (Epic 09).
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RawEmailFeatureConsumer {

    private static final Logger log = LoggerFactory.getLogger(RawEmailFeatureConsumer.class);

    private final EmailFeaturesService service;

    @Autowired
    public RawEmailFeatureConsumer(EmailFeaturesService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "${app.kafka.raw-topic.name:emails.raw}",
            groupId = "${app.kafka.feature-consumer.group-id:feature-extractor}",
            concurrency = "${app.kafka.feature-consumer.concurrency:3}")
    public void onRawEmail(RawEmailEvent event) {
        try {
            service.extractAndStore(event.emailId());
        } catch (Exception e) {
            // Backstop only: extraction is designed to be total. Surfaced, not
            // swallowed silently — the email is durable and replayable.
            log.error("feature extraction failed for id={}; skipping record", event.emailId(), e);
        }
    }
}
