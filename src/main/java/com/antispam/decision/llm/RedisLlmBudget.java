package com.antispam.decision.llm;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Redis-backed LLM spend cap (story 05.04). Spend is tracked in two counters — one per UTC day, one
 * per UTC calendar month — and every call must reserve against both before it runs, so cost is
 * bounded by design (PRD §Subsystem 5, §Cost). Active only when
 * {@code antispam.llm.budget.enabled=true}; otherwise {@link UnboundedLlmBudget} is wired in.
 *
 * <p><b>Atomic check+reserve.</b> The check-then-charge is a single Lua script
 * ({@link #RESERVE_LUA}), so it runs indivisibly on Redis's single thread: concurrent callers are
 * serialized and the cap is read and incremented as one step. There is no window in which two calls
 * both see room and both spend it (AC 1). The script charges a conservative upper-bound amount;
 * {@link #reconcile} later trues the counter down to the real cost, so the running total never
 * exceeds the cap between reserve and reconcile.
 *
 * <p><b>Windowing.</b> The two counters are keyed by the current day and month
 * ({@code llm:budget:v1:day:2026-06-22}, {@code llm:budget:v1:month:2026-06}), derived from the
 * injected {@link Clock} in UTC. A new day simply addresses a new key that starts at zero — the
 * daily counter "resets" with no scheduled job — while the month key is unchanged until the month
 * rolls over, so the monthly total continues across days (AC 5). Each key carries a TTL a little
 * longer than its window so stale counters self-evict.
 *
 * <p><b>Fail closed on outage.</b> Unlike the reputation cache, which degrades to Postgres when
 * Redis is down, the budget has no second source of truth. The only cost-safe response to "cannot
 * verify the budget" is to deny — better to suppress the expensive lever than risk unbounded spend.
 * A Redis error on reserve therefore returns a denial (with no cap scope, distinguishing it from a
 * real cap hit); a Redis error on reconcile is swallowed, leaving the conservative reservation
 * standing rather than crashing the decision path.
 */
@Component
@ConditionalOnProperty(name = "antispam.llm.budget.enabled", havingValue = "true")
public class RedisLlmBudget implements LlmBudget {

    private static final Logger log = LoggerFactory.getLogger(RedisLlmBudget.class);

    // Versioned key prefix so the layout can evolve without colliding with old counters.
    static final String DAY_PREFIX = "llm:budget:v1:day:";
    static final String MONTH_PREFIX = "llm:budget:v1:month:";

    // TTLs a little longer than each window, so a counter outlives its window (covering a
    // reconcile that lands just after midnight) and then self-evicts.
    private static final Duration DAY_TTL = Duration.ofDays(2);
    private static final Duration MONTH_TTL = Duration.ofDays(40);

    /** Script return codes: granted, or which cap denied the reservation. */
    private static final long GRANTED = 0L;
    private static final long DENIED_DAILY = 1L;
    private static final long DENIED_MONTHLY = 2L;

    /**
     * Atomic check+reserve. Checks the daily sub-cap first, then the monthly cap; only if neither
     * would be exceeded does it charge both counters and (re)set their TTLs. {@code INCRBYFLOAT}
     * keeps sub-cent costs exact. Returns 0 granted / 1 denied-daily / 2 denied-monthly.
     */
    private static final RedisScript<Long> RESERVE_LUA = new DefaultRedisScript<>(
            """
            local amount = tonumber(ARGV[1])
            local day = tonumber(redis.call('GET', KEYS[1]) or '0')
            if day + amount > tonumber(ARGV[2]) then return 1 end
            local month = tonumber(redis.call('GET', KEYS[2]) or '0')
            if month + amount > tonumber(ARGV[3]) then return 2 end
            redis.call('INCRBYFLOAT', KEYS[1], ARGV[1])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('INCRBYFLOAT', KEYS[2], ARGV[1])
            redis.call('EXPIRE', KEYS[2], ARGV[5])
            return 0
            """,
            Long.class);

    private final StringRedisTemplate redis;
    private final LlmBudgetProperties properties;
    private final Clock clock;

    public RedisLlmBudget(StringRedisTemplate redis, LlmBudgetProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public BudgetReservation tryReserve() {
        String dayKey = dayKey(clock);
        String monthKey = monthKey(clock);
        BigDecimal reserved = BigDecimal.valueOf(properties.perCallReservationUsd());
        try {
            Long code = redis.execute(
                    RESERVE_LUA,
                    List.of(dayKey, monthKey),
                    Double.toString(properties.perCallReservationUsd()),
                    Double.toString(properties.dailyCapUsd()),
                    Double.toString(properties.monthlyCapUsd()),
                    Long.toString(DAY_TTL.toSeconds()),
                    Long.toString(MONTH_TTL.toSeconds()));
            return interpret(code, reserved, dayKey, monthKey);
        } catch (RuntimeException e) {
            // No second source of truth for spend: the cost-safe move is to deny, not to spend
            // blind. Reported with no cap scope so it is distinguishable from a real cap hit.
            log.warn("LLM budget check failed against Redis, failing closed (denying): {}", e.toString());
            return BudgetReservation.unavailable();
        }
    }

    @Override
    public void reconcile(BudgetReservation reservation, BigDecimal actualCostUsd) {
        if (!reservation.granted()) {
            return;
        }
        BigDecimal delta = actualCostUsd.subtract(reservation.reservedUsd());
        if (delta.signum() == 0) {
            return;
        }
        try {
            // The reservation was an upper bound, so delta is normally negative — this releases the
            // unused reservation back. INCRBYFLOAT keeps the counter a true running total of spend.
            redis.opsForValue().increment(reservation.dayKey(), delta.doubleValue());
            redis.opsForValue().increment(reservation.monthKey(), delta.doubleValue());
        } catch (RuntimeException e) {
            // Best-effort: a failed reconcile just leaves the conservative reservation in place,
            // which can only under-spend the cap, never over. Never crash the decision path for it.
            log.warn("LLM budget reconcile failed against Redis, leaving reservation in place: {}",
                    e.toString());
        }
    }

    private static BudgetReservation interpret(
            Long code, BigDecimal reserved, String dayKey, String monthKey) {
        long c = code == null ? DENIED_MONTHLY : code;
        if (c == GRANTED) {
            return BudgetReservation.granted(reserved, dayKey, monthKey);
        }
        return BudgetReservation.denied(c == DENIED_DAILY ? BudgetScope.DAILY : BudgetScope.MONTHLY);
    }

    /** The current UTC day's counter key. Package-private for windowing unit tests. */
    static String dayKey(Clock clock) {
        return DAY_PREFIX + LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    /** The current UTC month's counter key. Package-private for windowing unit tests. */
    static String monthKey(Clock clock) {
        return MONTH_PREFIX + YearMonth.from(LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
    }
}
