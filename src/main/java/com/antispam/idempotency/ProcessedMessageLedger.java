package com.antispam.idempotency;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The processed-message ledger that makes Kafka consumers idempotent under
 * at-least-once delivery (story 02.03). Kafka redelivers — on retry, on
 * consumer-group rebalance, and on replay (Epic 09) — so a consumer must be able to
 * recognize a message it has already handled and skip its side effects, rather than
 * double-count features or (later) reputation. This is the project's single,
 * reusable idempotency mechanism: the feature extractor uses it today, and
 * reputation accrual (Epic 03) and feedback (Epic 07) lean on the same primitive.
 *
 * <p><b>How to use it.</b> Call {@link #claim} inside the <em>same transaction</em>
 * as the side effect it guards:
 *
 * <pre>{@code
 * @Transactional
 * void handle(Message m) {
 *     if (!ledger.claim(GROUP, m.naturalKey())) return; // already processed — skip
 *     doSideEffect(m);                                   // runs once per key
 * }
 * }</pre>
 *
 * Because the claim row and the side effect commit atomically, a crash between them
 * rolls back the claim and the message is reprocessed on redelivery — never recorded
 * as done without its effect, and never applied twice.
 */
@Component
public class ProcessedMessageLedger {

    /**
     * Counter (tagged {@code consumer.group}) incremented once per detected
     * redelivery, so at-least-once duplicates are observable rather than silent.
     */
    static final String DUPLICATE_COUNTER = "antispam.consumer.duplicate.detected";

    // A claim is a single atomic statement: the first delivery inserts the row and
    // affects one row; every redelivery hits the conflict and affects zero. This is
    // what makes claims correct under concurrent and out-of-order delivery — there is
    // no read-then-write race to lose.
    private static final String CLAIM_SQL = """
            insert into processed_messages (consumer_group, message_key)
            values (?, ?)
            on conflict (consumer_group, message_key) do nothing
            """;

    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;

    @Autowired
    public ProcessedMessageLedger(JdbcTemplate jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /**
     * Claims the right to process {@code messageKey} for {@code consumerGroup},
     * recording it as handled.
     *
     * @return {@code true} if this is the first delivery — the caller should perform
     *     the side effect; {@code false} if the group has already processed this key —
     *     the caller should skip (a {@value #DUPLICATE_COUNTER} duplicate is counted).
     */
    public boolean claim(String consumerGroup, String messageKey) {
        boolean firstDelivery = jdbc.update(CLAIM_SQL, consumerGroup, messageKey) == 1;
        if (!firstDelivery) {
            meters.counter(DUPLICATE_COUNTER, "consumer.group", consumerGroup).increment();
        }
        return firstDelivery;
    }
}
