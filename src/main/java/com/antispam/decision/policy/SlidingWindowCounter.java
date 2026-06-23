package com.antispam.decision.policy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A Redis-backed sliding-window event counter (story 06.01). For a key, it records one event and
 * returns how many events fall within the trailing window — a <em>true</em> sliding window, not a
 * fixed bucket: each event ages out exactly one window after it happened, so the count rises while
 * traffic is dense and falls back to zero once it stops (PRD §Subsystem 4 runtime layer). The burst
 * detector keys it per sender to catch a sudden blast by velocity; story 06.02 reuses it keyed per
 * content signature.
 *
 * <p><b>Atomic record-and-count.</b> The age-out, record, and count are one Lua script
 * ({@link #RECORD_AND_COUNT_LUA}) so they run indivisibly on Redis's single thread: concurrent
 * events on the same key are serialized and no event sees a half-updated window. The window is a
 * sorted set scored by event time; {@code ZREMRANGEBYSCORE} drops everything older than one window,
 * {@code ZADD} records the new event under a caller-supplied unique member, and {@code ZCARD}
 * returns the surviving count. A {@code PEXPIRE} of one window length lets an idle key self-evict,
 * bounding memory to active senders.
 *
 * <p>Only created when {@code antispam.burst.enabled=true}, so a deployment without burst detection
 * never instantiates a Redis-touching bean (matching the reputation cache and budget pattern).
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "true")
public class SlidingWindowCounter {

    /**
     * Age out events older than one window, record this event, bound the key's lifetime to one
     * window, and return the count surviving in the window. {@code ARGV}: now-millis, window-millis,
     * unique member. Members must be unique per event (the caller passes the email id), so a genuine
     * blast of distinct messages each adds a count while a redelivery of the same message does not.
     */
    private static final RedisScript<Long> RECORD_AND_COUNT_LUA = new DefaultRedisScript<>(
            """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            redis.call('ZADD', KEYS[1], now, ARGV[3])
            redis.call('PEXPIRE', KEYS[1], window)
            return redis.call('ZCARD', KEYS[1])
            """,
            Long.class);

    private final StringRedisTemplate redis;

    @Autowired
    public SlidingWindowCounter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Records an event on {@code key} at {@code now} under the unique {@code member} and returns the
     * number of events within the trailing {@code window} (including this one).
     *
     * @param key    the window's Redis key (caller namespaces it, e.g. {@code burst:v1:sender:...})
     * @param member a token unique to this event, so re-recording the same event does not inflate
     *               the count — the email id is a natural choice
     * @param now    the event time
     * @param window the trailing window over which events are counted
     * @return the count of events in {@code (now - window, now]}, at least 1
     */
    public long recordAndCount(String key, String member, Instant now, Duration window) {
        Long count = redis.execute(
                RECORD_AND_COUNT_LUA,
                List.of(key),
                Long.toString(now.toEpochMilli()),
                Long.toString(window.toMillis()),
                member);
        return count == null ? 0L : count;
    }
}
