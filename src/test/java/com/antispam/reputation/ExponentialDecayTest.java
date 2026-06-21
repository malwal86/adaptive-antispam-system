package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * The exponential time-decay math in isolation (story 03.02): the fraction of a
 * reputation event's weight that survives as it ages, {@code 0.5 ^ (age / halfLife)}.
 * Pure unit — no Spring, no clock, no database — so the analytic identities and the
 * one invariant the rest of the epic leans on are pinned directly.
 *
 * <p>The invariant that matters is <em>composability</em>: surviving(a+b) ==
 * surviving(a)·surviving(b). It is why decaying an event once at read time equals
 * decaying it in folded steps across writes, which is what makes the read path and
 * the write path agree (AC 3) and the score independent of when it was read
 * in between (the path-independence property).
 */
class ExponentialDecayTest {

    private static final Duration HALF_LIFE = Duration.ofDays(7);
    private static final ExponentialDecay DECAY = new ExponentialDecay(HALF_LIFE);

    @Test
    void a_fresh_event_keeps_its_full_weight() {
        assertThat(DECAY.survivingFraction(Duration.ZERO)).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void weight_halves_each_half_life() {
        assertThat(DECAY.survivingFraction(HALF_LIFE)).isCloseTo(0.5, within(1e-12));
        assertThat(DECAY.survivingFraction(HALF_LIFE.multipliedBy(2))).isCloseTo(0.25, within(1e-12));
        assertThat(DECAY.survivingFraction(HALF_LIFE.multipliedBy(3))).isCloseTo(0.125, within(1e-12));
    }

    @Test
    void half_a_half_life_leaves_one_over_root_two() {
        // 0.5 ^ 0.5 = 1/sqrt(2) -- the midpoint check that the exponent is age/halfLife,
        // not a linear interpolation.
        assertThat(DECAY.survivingFraction(HALF_LIFE.dividedBy(2)))
                .isCloseTo(1.0 / Math.sqrt(2), within(1e-12));
    }

    @Test
    void decay_is_strictly_monotonic_in_age() {
        double oneDay = DECAY.survivingFraction(Duration.ofDays(1));
        double oneWeek = DECAY.survivingFraction(Duration.ofDays(7));
        double oneMonth = DECAY.survivingFraction(Duration.ofDays(30));

        assertThat(oneDay).isGreaterThan(oneWeek);
        assertThat(oneWeek).isGreaterThan(oneMonth);
        assertThat(oneDay).isLessThan(1.0);
    }

    @Test
    void surviving_fraction_composes_multiplicatively() {
        // The load-bearing invariant: decaying across a then b equals decaying across
        // a+b in one step. This is exactly why folding decay at a write and finishing
        // it at read time yields the same surviving weight as decaying once at read --
        // i.e. read/write consistency and path independence (AC 3, property test).
        Duration a = Duration.ofDays(3);
        Duration b = Duration.ofDays(5);

        double twoStep = DECAY.survivingFraction(a) * DECAY.survivingFraction(b);
        double oneStep = DECAY.survivingFraction(a.plus(b));

        assertThat(twoStep).isCloseTo(oneStep, within(1e-12));
    }

    @Test
    void surviving_fraction_between_two_instants_uses_their_gap() {
        Instant occurredAt = Instant.parse("2026-06-01T00:00:00Z");
        Instant readAt = occurredAt.plus(HALF_LIFE);

        assertThat(DECAY.survivingFraction(occurredAt, readAt)).isCloseTo(0.5, within(1e-12));
    }

    @Test
    void a_negative_age_cannot_amplify_weight() {
        // An event timestamped after the read instant (clock skew) must not decay to
        // more than its original weight: a negative age clamps to full weight, never
        // a factor above 1.0.
        assertThat(DECAY.survivingFraction(Duration.ofDays(-7))).isCloseTo(1.0, within(1e-12));
    }

    @Test
    void half_life_is_exposed_in_seconds_for_the_sql_term() {
        assertThat(DECAY.halfLifeSeconds()).isCloseTo(7.0 * 24 * 60 * 60, within(1e-9));
    }

    @Test
    void rejects_a_non_positive_half_life() {
        assertThatThrownBy(() -> new ExponentialDecay(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialDecay(Duration.ofDays(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExponentialDecay(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
