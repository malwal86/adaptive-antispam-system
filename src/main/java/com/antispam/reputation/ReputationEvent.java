package com.antispam.reputation;

import java.time.Instant;

/**
 * One row of the append-only {@code reputation_events} log — a single
 * reputation-affecting signal for a sender (story 03.01). The log is the source of
 * truth: the sender's Beta score is recomputed from these rows, and the
 * {@code senders}/Redis caches are rebuildable by replaying them, so a row is
 * written once and never mutated (the table enforces this).
 *
 * @param id          surrogate key assigned by the database on insert; {@code null}
 *                    on an event that has not been persisted yet
 * @param senderKey   the sender this signal is about (com.antispam.event.SenderKey)
 * @param signal      whether this counts as a good or bad observation
 * @param weight      how much it counts toward its bucket; {@code > 0}. 1.0 today,
 *                    fractional once soft auth-gating lands (story 03.03)
 * @param decayFactor a per-event decay multiplier in {@code (0, 1]}; always 1.0.
 *                    Read-time decay (story 03.02) is computed from {@code occurredAt}
 *                    against the read instant, not from a stored factor, so this column
 *                    stays 1.0 — reserved for a future write-time fold optimization of
 *                    the senders/Redis cache (stories 03.04/03.05)
 * @param source      provenance of the signal (e.g. {@code decision}, {@code feedback},
 *                    {@code api}); non-blank
 * @param bucket      which accrual bucket the signal lands in, decided by the email's
 *                    DMARC alignment (story 03.03); part of the audit trail; non-null
 * @param occurredAt  when the signal happened; {@code null} before persistence
 *                    stamps it (the database defaults it), non-null when read back
 */
public record ReputationEvent(
        Long id,
        String senderKey,
        ReputationSignal signal,
        double weight,
        double decayFactor,
        String source,
        ReputationBucket bucket,
        Instant occurredAt) {

    public ReputationEvent {
        if (senderKey == null || senderKey.isBlank()) {
            throw new IllegalArgumentException("senderKey must not be blank");
        }
        if (signal == null) {
            throw new IllegalArgumentException("signal must not be null");
        }
        if (weight <= 0) {
            throw new IllegalArgumentException("weight must be positive: " + weight);
        }
        if (decayFactor <= 0 || decayFactor > 1) {
            throw new IllegalArgumentException("decayFactor must be in (0, 1]: " + decayFactor);
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (bucket == null) {
            throw new IllegalArgumentException("bucket must not be null");
        }
    }

    /**
     * A new, not-yet-persisted event at full decay (1.0) for the given accrual bucket —
     * the shape every caller appends today. {@code id} and {@code occurredAt} are left
     * for the database to assign.
     */
    public static ReputationEvent of(
            String senderKey, ReputationSignal signal, double weight, String source, ReputationBucket bucket) {
        return new ReputationEvent(null, senderKey, signal, weight, 1.0, source, bucket, null);
    }
}
