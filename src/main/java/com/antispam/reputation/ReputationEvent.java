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
 * @param decayFactor time-decay applied to this event; in {@code (0, 1]}. Always 1.0
 *                    today; story 03.02 fills it with the read-time decay
 * @param source      provenance of the signal (e.g. {@code decision}, {@code feedback},
 *                    {@code api}); non-blank
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
    }

    /**
     * A new, not-yet-persisted event at full decay (1.0) — the shape every caller
     * appends today. {@code id} and {@code occurredAt} are left for the database to
     * assign.
     */
    public static ReputationEvent of(String senderKey, ReputationSignal signal, double weight, String source) {
        return new ReputationEvent(null, senderKey, signal, weight, 1.0, source, null);
    }
}
