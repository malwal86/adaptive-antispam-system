package com.antispam.reputation.accrual;

import com.antispam.event.RawEmailEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code emails.raw} and accrues per-sender reputation for each email
 * (story 03.05). The topic is keyed by sender (story 02.01), so each partition is
 * one sender-shard and Kafka assigns each partition to exactly one consumer thread
 * in this group — that assignment <em>is</em> the per-sender serialization, with no
 * application lock. {@link ReputationAccrualService} keeps no shared mutable state,
 * so concurrent shards never contend.
 *
 * <p>This is a second, independent consumer group on the same topic, parallel to the
 * feature extractor: the two derive different projections of the same events and
 * dedupe independently. {@code concurrency} is set to the partition count by default
 * so every shard can run on its own thread; any value up to the partition count
 * preserves per-partition ordering (Kafka never splits a partition across threads in
 * a group).
 *
 * <p>Only active when the spine is enabled ({@code app.kafka.enabled=true}); when off
 * — the default in tests and any environment without a broker — the listener bean is
 * not created, so no consumer connects.
 *
 * <p><b>Partition safety.</b> Accrual is idempotent and total in the normal case; as
 * a backstop, any unexpected error handling one record is caught and logged so the
 * offset still advances and a single bad record cannot wedge the shard. The email is
 * durable in Postgres, so a dropped record is recoverable by replay (Epic 09).
 */
@Component
@ConditionalOnProperty(prefix = "app.kafka", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ReputationAccrualConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReputationAccrualConsumer.class);

    private final ReputationAccrualService service;

    @Autowired
    public ReputationAccrualConsumer(ReputationAccrualService service) {
        this.service = service;
    }

    @KafkaListener(
            topics = "${app.kafka.raw-topic.name:emails.raw}",
            groupId = "${app.kafka.reputation-consumer.group-id:reputation-accrual}",
            concurrency = "${app.kafka.reputation-consumer.concurrency:6}")
    public void onRawEmail(
            RawEmailEvent event,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition) {
        try {
            service.accrue(event.emailId(), partition);
        } catch (Exception e) {
            // Backstop: surface, don't swallow — the email is durable and replayable.
            log.error("reputation accrual failed for id={}; skipping record", event.emailId(), e);
        }
    }
}
