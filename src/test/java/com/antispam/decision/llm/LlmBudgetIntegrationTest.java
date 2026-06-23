package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the atomic Redis budget cap (story 05.04) against real Redis. The caps are
 * pinned small and exact-in-binary (reservation 0.25, daily 1.00, monthly 1.50) so the counters
 * never wobble, and a settable clock lets a reservation happen "tomorrow" without sleeping.
 *
 * <p>It pins every acceptance criterion: concurrent reservations at the boundary never overspend
 * (AC 1 — the atomicity that a mocked test cannot prove); the daily sub-cap blocks while the month
 * still has room (AC 2); the monthly cap blocks while the day has room (AC 3); reconciliation
 * leaves the counter equal to real spend (AC 4); and a day rollover resets the daily counter while
 * the monthly continues (AC 5).
 */
@TestPropertySource(properties = {
        "antispam.llm.budget.enabled=true",
        "antispam.llm.budget.per-call-reservation-usd=0.25",
        "antispam.llm.budget.daily-cap-usd=1.00",
        "antispam.llm.budget.monthly-cap-usd=1.50",
})
class LlmBudgetIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-22T12:00:00Z");
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(BASE);

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            REDIS.start();
        }
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class SettableClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return new Clock() {
                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public Instant instant() {
                    return NOW.get();
                }
            };
        }
    }

    @Autowired
    private LlmBudget budget;

    @Autowired
    private StringRedisTemplate redis;

    @BeforeEach
    void resetClockAndRedis() {
        NOW.set(BASE);
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
    }

    @Test
    void concurrent_reservations_at_the_boundary_never_overspend() throws InterruptedException {
        // 40 callers race for a daily cap that holds exactly four 0.25 reservations. Atomicity must
        // let through at most four — no race may let two callers both claim the last slot.
        int callers = 40;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger granted = new AtomicInteger();
        for (int i = 0; i < callers; i++) {
            pool.submit(() -> {
                await(start);
                if (budget.tryReserve().granted()) {
                    granted.incrementAndGet();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(20, TimeUnit.SECONDS)).isTrue();

        assertThat(granted.get()).isEqualTo(4); // 4 * 0.25 == 1.00, the cap exactly
        assertThat(counter(dayKey())).isEqualTo(1.00); // 0 overspend: never above the cap
        assertThat(counter(monthKey())).isEqualTo(1.00);
    }

    @Test
    void the_daily_sub_cap_blocks_while_the_monthly_cap_still_has_room() {
        fillDaily(); // four grants -> daily at 1.00, month at 1.00 (monthly cap is 1.50)

        BudgetReservation denied = budget.tryReserve();

        assertThat(denied.granted()).isFalse();
        assertThat(denied.deniedScope()).isEqualTo(BudgetScope.DAILY);
        assertThat(counter(monthKey())).isEqualTo(1.00); // the month was not charged for the denial
    }

    @Test
    void the_monthly_cap_blocks_for_the_rest_of_the_month_while_the_day_has_room() {
        fillDaily(); // day 1: month at 1.00
        NOW.set(BASE.plus(java.time.Duration.ofDays(1))); // roll to day 2 — daily resets

        assertThat(budget.tryReserve().granted()).isTrue(); // month 1.25, day2 0.25
        assertThat(budget.tryReserve().granted()).isTrue(); // month 1.50, day2 0.50

        BudgetReservation denied = budget.tryReserve(); // month 1.50 + 0.25 > 1.50
        assertThat(denied.granted()).isFalse();
        assertThat(denied.deniedScope()).isEqualTo(BudgetScope.MONTHLY);
        assertThat(counter(dayKey())).isEqualTo(0.50); // the day still had room — it was the month
    }

    @Test
    void a_day_rollover_resets_the_daily_counter_while_the_monthly_continues() {
        assertThat(budget.tryReserve().granted()).isTrue(); // day 1: 0.25
        NOW.set(BASE.plus(java.time.Duration.ofDays(1)));
        assertThat(budget.tryReserve().granted()).isTrue(); // day 2: fresh 0.25, not 0.50

        assertThat(counter(dayKey())).isEqualTo(0.25);   // the new day started from zero
        assertThat(counter(monthKey())).isEqualTo(0.50); // the month carried both days
    }

    @Test
    void reconciliation_leaves_the_counter_equal_to_real_spend() {
        // Reserve the 0.25 upper bound three times, truing each up to its real (smaller) cost. The
        // counter must end at the sum of the actual costs, not the sum of the reservations.
        double[] actuals = {0.02, 0.0125, 0.03125}; // all exact in binary
        double expected = 0.0;
        for (double actual : actuals) {
            BudgetReservation reservation = budget.tryReserve();
            assertThat(reservation.granted()).isTrue();
            budget.reconcile(reservation, BigDecimal.valueOf(actual));
            expected += actual;
        }

        assertThat(counter(dayKey())).isCloseTo(expected, within(1e-9));
        assertThat(counter(monthKey())).isCloseTo(expected, within(1e-9));
    }

    private void fillDaily() {
        for (int i = 0; i < 4; i++) {
            assertThat(budget.tryReserve().granted()).isTrue();
        }
    }

    private double counter(String key) {
        String raw = redis.opsForValue().get(key);
        return raw == null ? 0.0 : Double.parseDouble(raw);
    }

    private static String dayKey() {
        return RedisLlmBudget.DAY_PREFIX + LocalDate.ofInstant(NOW.get(), ZoneOffset.UTC);
    }

    private static String monthKey() {
        return RedisLlmBudget.MONTH_PREFIX
                + YearMonth.from(LocalDate.ofInstant(NOW.get(), ZoneOffset.UTC));
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
