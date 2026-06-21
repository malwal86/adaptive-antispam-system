package com.antispam.reputation;

import java.time.Duration;
import java.time.Instant;

/**
 * A sender's reputation as held in the Redis materialized cache (story 03.04): the
 * bucketed good/bad counts <em>folded</em> (decayed) to a single {@code foldedAt}
 * instant. This is the {@code (good, bad, last_update_ts, buckets)} state the PRD calls
 * for — just enough to apply lazy read-time decay (story 03.02) without replaying
 * {@code reputation_events} on every read.
 *
 * <p>Postgres remains the source of truth; this is a derived snapshot, always
 * rebuildable from the event log. Its correctness rests on the composability of
 * exponential decay: counts decayed to {@code foldedAt} and then aged forward to the
 * read instant equal the counts obtained by decaying every underlying event straight to
 * the read instant. So {@link #decayedTo} can serve a correct, lazily-decayed read from
 * one cache entry, and a rebuild ({@code countsFor} at some instant, stored with
 * {@code foldedAt} = that instant) round-trips to the same value.
 *
 * @param foldedCounts bucketed good/bad totals decayed to {@code foldedAt}
 * @param foldedAt     the instant the counts are decayed to (the "last update" time)
 */
public record CachedReputation(BucketedReputationCounts foldedCounts, Instant foldedAt) {

    /**
     * The bucketed counts aged forward from {@link #foldedAt} to {@code readAt} under
     * {@code decay}: every bucket is scaled by {@code survivingFraction(readAt-foldedAt)}.
     * A {@code readAt} before {@code foldedAt} (clock skew) clamps to the stored counts —
     * {@link ExponentialDecay} never amplifies.
     */
    public BucketedReputationCounts decayedTo(Instant readAt, ExponentialDecay decay) {
        double factor = decay.survivingFraction(Duration.between(foldedAt, readAt));
        return foldedCounts.scaledBy(factor);
    }
}
