package com.antispam.reputation.cache;

import com.antispam.reputation.CachedReputation;
import java.util.Optional;

/**
 * The materialized reputation read cache (story 03.04): a fast store of each sender's
 * folded {@link CachedReputation} snapshot, sitting in front of the Postgres
 * {@code reputation_events} source of truth.
 *
 * <p>The cache is <b>derived, never authoritative</b>. A miss is not an error — the
 * caller rebuilds the entry by replaying events from Postgres and re-populates. Equally,
 * an unavailable cache (Redis outage) must never surface as a failed or wrong read: an
 * implementation degrades by reporting a miss ({@link #get} returns empty) and silently
 * dropping writes, so reputation always falls back to Postgres-backed computation.
 */
public interface ReputationReadCache {

    /**
     * The cached snapshot for a sender, or empty on a miss <em>or</em> when the cache is
     * unavailable. Callers treat both the same way: rebuild from the event log.
     */
    Optional<CachedReputation> get(String senderKey);

    /** Stores/refreshes a sender's snapshot. A failure to write is swallowed (best-effort). */
    void put(String senderKey, CachedReputation entry);
}
