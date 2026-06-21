package com.antispam.reputation;

/**
 * A sender's decayed good/bad totals split by accrual {@link ReputationBucket}, summed
 * from the {@code reputation_events} log (story 03.03). Soft auth-gating keeps the
 * authenticated and unauthenticated histories apart so they can be blended under the
 * neutral cap at read time ({@link GatedReputation}); this is the raw, per-bucket
 * evidence before any cap is applied — the bridge between the repository's SQL
 * aggregation and the gated read math.
 *
 * @param authenticated   decayed good/bad from DMARC-aligned mail (full-trust bucket)
 * @param unauthenticated decayed good/bad from mail that did not prove alignment
 */
public record BucketedReputationCounts(
        ReputationCounts authenticated,
        ReputationCounts unauthenticated) {

    /**
     * Both buckets scaled by {@code factor} — ages a folded cache snapshot forward to
     * the read instant (story 03.04) by applying one read-time decay factor to the
     * aggregate, which equals per-event decay by composability (story 03.02).
     */
    public BucketedReputationCounts scaledBy(double factor) {
        return new BucketedReputationCounts(
                authenticated.scaledBy(factor), unauthenticated.scaledBy(factor));
    }
}
