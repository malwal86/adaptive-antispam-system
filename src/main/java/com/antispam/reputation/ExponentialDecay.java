package com.antispam.reputation;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential read-time decay of reputation evidence (PRD §Subsystem 3, story 03.02).
 * A reputation event's weight fades with age so that stale good behaviour can't
 * indefinitely shield a sender that turns bad: at {@code age == 0} an event keeps its
 * full weight, at one {@link #halfLife() half-life} it keeps half, at two a quarter,
 * following {@code 0.5 ^ (age / halfLife)}.
 *
 * <p>This is a pure value object — no clock, no I/O — so an event's surviving weight
 * is a function of its age and nothing else. That purity is what lets decay be a lazy
 * <em>read-time</em> computation with no decay cron (AC 4): every read re-derives each
 * event's surviving weight from its {@code occurred_at} against the read instant,
 * rather than a scheduled job rewriting stored counts.
 *
 * <p><b>Composability is the load-bearing invariant.</b> Because the factor is
 * exponential, {@code surviving(a + b) == surviving(a) · surviving(b)}: decaying an
 * event across two steps (folded once at a write, finished at read) yields exactly the
 * same surviving weight as decaying it once over the whole span at read time. That is
 * precisely why the read path and the write path agree (AC 3) and why the score is
 * independent of when it happened to be read in between (the path-independence
 * property). {@link ReputationRepository#countsFor} implements this same factor in SQL
 * to aggregate in one pass; this class is that query's executable specification.
 *
 * @param halfLife the age at which an event's weight halves; must be positive
 */
public record ExponentialDecay(Duration halfLife) {

    public ExponentialDecay {
        if (halfLife == null || halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException("halfLife must be positive: " + halfLife);
        }
    }

    /**
     * The fraction of an event's weight that survives at {@code age}: {@code 1.0} at
     * age zero, {@code 0.5} at one half-life, approaching {@code 0} as age grows. A
     * negative age (an event timestamped after the read instant — e.g. clock skew) is
     * clamped to zero so decay can only ever shrink weight, never amplify it.
     */
    public double survivingFraction(Duration age) {
        double ageSeconds = Math.max(0.0, age.toNanos() / 1e9);
        return Math.pow(0.5, ageSeconds / halfLifeSeconds());
    }

    /**
     * {@link #survivingFraction(Duration)} for an event that occurred at
     * {@code occurredAt} and is read at {@code readAt}.
     */
    public double survivingFraction(Instant occurredAt, Instant readAt) {
        return survivingFraction(Duration.between(occurredAt, readAt));
    }

    /** The half-life expressed in seconds — the denominator the SQL decay term binds. */
    public double halfLifeSeconds() {
        return halfLife.toNanos() / 1e9;
    }
}
