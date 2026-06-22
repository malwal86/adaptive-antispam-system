package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The Redis budget's non-atomic edges, unit-level (story 05.04): how the Lua return code maps to a
 * reservation, the fail-closed-on-outage contract, the reconcile math, and the UTC day/month
 * windowing keys. The atomic check+reserve itself runs in Lua and is pinned end-to-end against real
 * Redis in {@link LlmBudgetIntegrationTest} — here the script execution is stubbed so the
 * surrounding Java logic is tested in isolation.
 */
@ExtendWith(MockitoExtension.class)
class RedisLlmBudgetTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC);
    private static final LlmBudgetProperties PROPS = new LlmBudgetProperties(true, 0.50, 5.00, 0.01);

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private ValueOperations<String, String> valueOps;

    private RedisLlmBudget budget() {
        return new RedisLlmBudget(redis, PROPS, FIXED);
    }

    @SuppressWarnings("unchecked")
    private void stubScript(Long code) {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenReturn(code);
    }

    @Test
    void a_zero_return_grants_a_reservation_for_the_current_day_and_month() {
        stubScript(0L);

        BudgetReservation reservation = budget().tryReserve();

        assertThat(reservation.granted()).isTrue();
        assertThat(reservation.reservedUsd()).isEqualByComparingTo("0.01");
        assertThat(reservation.dayKey()).isEqualTo("llm:budget:v1:day:2026-06-22");
        assertThat(reservation.monthKey()).isEqualTo("llm:budget:v1:month:2026-06");
    }

    @Test
    void a_one_return_denies_against_the_daily_sub_cap() {
        stubScript(1L);

        BudgetReservation reservation = budget().tryReserve();

        assertThat(reservation.granted()).isFalse();
        assertThat(reservation.deniedScope()).isEqualTo(BudgetScope.DAILY);
    }

    @Test
    void a_two_return_denies_against_the_monthly_cap() {
        stubScript(2L);

        BudgetReservation reservation = budget().tryReserve();

        assertThat(reservation.granted()).isFalse();
        assertThat(reservation.deniedScope()).isEqualTo(BudgetScope.MONTHLY);
    }

    @Test
    void fails_closed_with_no_scope_when_redis_is_unreachable() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        BudgetReservation reservation = budget().tryReserve();

        // Denied — the cost-safe response when spend cannot be verified — but with no cap scope, so
        // it is distinguishable from a real cap hit.
        assertThat(reservation.granted()).isFalse();
        assertThat(reservation.deniedScope()).isNull();
    }

    @Test
    void reconcile_releases_the_unused_reservation_back_to_both_windows() {
        when(redis.opsForValue()).thenReturn(valueOps);
        BudgetReservation reservation =
                BudgetReservation.granted(new BigDecimal("0.01"), "dayK", "monthK");

        // Actual cost 0.002 against a 0.01 reservation: a -0.008 delta released to each window.
        budget().reconcile(reservation, new BigDecimal("0.002"));

        verify(valueOps).increment("dayK", -0.008);
        verify(valueOps).increment("monthK", -0.008);
    }

    @Test
    void reconcile_does_nothing_for_a_denied_reservation() {
        budget().reconcile(BudgetReservation.denied(BudgetScope.DAILY), new BigDecimal("0.02"));

        verifyNoInteractions(redis);
    }

    @Test
    void reconcile_does_nothing_when_the_actual_cost_equals_the_reservation() {
        BudgetReservation reservation =
                BudgetReservation.granted(new BigDecimal("0.01"), "dayK", "monthK");

        budget().reconcile(reservation, new BigDecimal("0.01"));

        verify(redis, never()).opsForValue();
    }

    @Test
    void reconcile_failure_against_redis_is_swallowed() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(eq("dayK"), any(Double.class)))
                .thenThrow(new RedisConnectionFailureException("down"));
        BudgetReservation reservation =
                BudgetReservation.granted(new BigDecimal("0.01"), "dayK", "monthK");

        // A failed reconcile must never propagate into the decision path.
        budget().reconcile(reservation, new BigDecimal("0.002"));
    }

    @Test
    void windowing_keys_track_the_clock_in_utc() {
        Clock nearMidnight =
                Clock.fixed(Instant.parse("2026-01-31T23:59:59Z"), ZoneOffset.UTC);
        Clock nextDay = Clock.fixed(Instant.parse("2026-02-01T00:00:01Z"), ZoneOffset.UTC);

        // A new day addresses a new daily key (so the daily counter "resets" with no job)...
        assertThat(RedisLlmBudget.dayKey(nearMidnight)).isEqualTo("llm:budget:v1:day:2026-01-31");
        assertThat(RedisLlmBudget.dayKey(nextDay)).isEqualTo("llm:budget:v1:day:2026-02-01");
        // ...and crossing month-end addresses a new monthly key too.
        assertThat(RedisLlmBudget.monthKey(nearMidnight)).isEqualTo("llm:budget:v1:month:2026-01");
        assertThat(RedisLlmBudget.monthKey(nextDay)).isEqualTo("llm:budget:v1:month:2026-02");
    }
}
