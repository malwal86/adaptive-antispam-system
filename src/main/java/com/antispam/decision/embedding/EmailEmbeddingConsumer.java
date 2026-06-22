package com.antispam.decision.embedding;

import com.antispam.event.RawEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code emails.raw} and embeds each email onto the {@code email_embeddings}
 * pgvector table (story 04.03), off the synchronous decision path. It is a second,
 * independent consumer group alongside the feature extractor: embeddings feed
 * offline clustering (Epic 06), not the live verdict, so computing them on the
 * spine keeps them off the <100ms budget.
 *
 * <p>Mirrors {@code RawEmailFeatureConsumer}: only active when the spine is enabled
 * ({@code app.kafka.enabled=true}); {@link EmailEmbeddingService} enforces
 * exactly-once embedding under at-least-once delivery via the processed-message
 * ledger; and any unexpected error handling one record is caught and logged so the
 * offset still advances — the email is durable in Postgres and replayable (Epic 09),
 * so one bad record cannot wedge the shard.
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmailEmbeddingConsumer {

    private static final Logger log = LoggerFactory.getLogger(EmailEmbeddingConsumer.class);

    private final EmailEmbeddingService service;

    @Autowired
    public EmailEmbeddingConsumer(EmailEmbeddingService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "${app.kafka.raw-topic.name:emails.raw}",
            groupId = "${app.kafka.embedding-consumer.group-id:embedding-extractor}",
            concurrency = "${app.kafka.embedding-consumer.concurrency:3}")
    public void onRawEmail(RawEmailEvent event) {
        try {
            service.embedAndStore(event.emailId());
        } catch (Exception e) {
            // Backstop: embedding is best-effort and replayable. Surfaced, not
            // swallowed silently — the email is durable.
            log.error("embedding failed for id={}; skipping record", event.emailId(), e);
        }
    }
}
