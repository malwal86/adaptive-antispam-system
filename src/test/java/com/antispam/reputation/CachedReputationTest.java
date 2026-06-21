package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The Redis cache snapshot math in isolation (story 03.04): a sender's bucketed counts
 * folded (decayed) to a single {@code foldedAt} instant, then aged forward to the read
 * instant on demand. Pure unit — no Redis, no clock — so the one property the cache
 * relies on is pinned: aging a folded snapshot forward yields the same counts as
 * decaying the underlying events directly, because exponential decay composes
 * (story 03.02). That is what lets a single {@code (good, bad, last_update_ts)} entry
 * serve correct, lazily-decayed reads without replaying Postgres.
 */
class CachedReputationTest {

    private static final ExponentialDecay DECAY = new ExponentialDecay(Duration.ofDays(7));
    private static final Instant FOLDED_AT = Instant.parse("2026-06-21T12:00:00Z");

    private static CachedReputation snapshot(double ag, double ab, double ug, double ub) {
        return new CachedReputation(
                new BucketedReputationCounts(new ReputationCounts(ag, ab), new ReputationCounts(ug, ub)),
                FOLDED_AT);
    }

    @Test
    void reading_at_the_fold_instant_returns_the_stored_counts() {
        CachedReputation entry = snapshot(8, 2, 3, 1);

        BucketedReputationCounts now = entry.decayedTo(FOLDED_AT, DECAY);

        assertThat(now.authenticated().good()).isCloseTo(8.0, within(1e-12));
        assertThat(now.authenticated().bad()).isCloseTo(2.0, within(1e-12));
        assertThat(now.unauthenticated().good()).isCloseTo(3.0, within(1e-12));
        assertThat(now.unauthenticated().bad()).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void reading_one_half_life_later_halves_every_bucket() {
        CachedReputation entry = snapshot(8, 2, 4, 6);

        BucketedReputationCounts later = entry.decayedTo(FOLDED_AT.plus(Duration.ofDays(7)), DECAY);

        assertThat(later.authenticated().good()).isCloseTo(4.0, within(1e-12));
        assertThat(later.authenticated().bad()).isCloseTo(1.0, within(1e-12));
        assertThat(later.unauthenticated().good()).isCloseTo(2.0, within(1e-12));
        assertThat(later.unauthenticated().bad()).isCloseTo(3.0, within(1e-12));
    }

    @Test
    void aging_forward_composes_so_a_snapshot_matches_a_one_step_decay() {
        // Folding to t1 then aging t1->t2 must equal decaying straight to t2. This is the
        // cache's correctness guarantee versus a Postgres replay.
        CachedReputation atFold = snapshot(10, 4, 0, 0);
        Instant t2 = FOLDED_AT.plus(Duration.ofDays(11));

        double viaSnapshot = atFold.decayedTo(t2, DECAY).authenticated().good();
        double oneStep = 10.0 * DECAY.survivingFraction(Duration.ofDays(11));

        assertThat(viaSnapshot).isCloseTo(oneStep, within(1e-12));
    }

    @Test
    void a_read_before_the_fold_instant_does_not_amplify() {
        // Clock skew: reading slightly before foldedAt clamps to the stored counts
        // (ExponentialDecay clamps negative ages), never a factor above 1.
        CachedReputation entry = snapshot(5, 5, 0, 0);

        BucketedReputationCounts earlier = entry.decayedTo(FOLDED_AT.minus(Duration.ofDays(3)), DECAY);

        assertThat(earlier.authenticated().good()).isCloseTo(5.0, within(1e-12));
    }
}
