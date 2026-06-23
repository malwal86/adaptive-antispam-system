package com.antispam.decision.policy;

import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A Redis-backed near-duplicate index over recent content fingerprints (story 06.02). Each email's
 * {@link SimHasher} fingerprint is compared against the recent fingerprints still in the window; the
 * count within a small Hamming radius is the size of its runtime campaign cluster, which feeds the
 * burst override (06.01) so a templated blast escalates even when spread across many senders (PRD
 * §Subsystem 4). This is the cheap surface tier — no embeddings — paired with the expensive offline
 * embedding clustering (06.03) that catches reworded variants this misses.
 *
 * <p><b>Bounded and cheap.</b> The recent fingerprints live in a sorted set scored by event time;
 * one Lua script ages out everything older than the window, snapshots the survivors, records the new
 * fingerprint, caps the set to {@link #RECENT_CAP} most-recent entries, and bounds the key's lifetime
 * to one window — so memory is bounded (AC 5) and the per-email comparison is {@code O(cap)} bit-count
 * operations with no embedding call (AC 4). The Hamming comparison runs in Java, not Lua, because Lua
 * numbers cannot hold a 64-bit fingerprint without precision loss.
 *
 * <p>Members are {@code <fingerprint-hex>:<email-id>} so that exact duplicates from different emails
 * are distinct entries (a templated blast of identical bodies still accumulates) while a redelivery
 * of the same email does not double-count. Only created when {@code antispam.burst.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "antispam.burst.enabled", havingValue = "true")
public class NearDuplicateIndex {

    /** Single global key — the index spans senders so a cross-sender campaign clusters together. */
    static final String KEY = "neardup:v1:fingerprints";

    /**
     * The most recent fingerprints retained for comparison. Caps both memory and the per-email
     * comparison cost; older fingerprints age out by window before this bound usually bites.
     */
    static final int RECENT_CAP = 512;

    private static final char MEMBER_DELIMITER = ':';

    /**
     * Age out fingerprints older than one window, snapshot the survivors (before adding this one),
     * record the new fingerprint, cap the set to the most-recent {@code RECENT_CAP}, and bound the
     * key's lifetime to one window. {@code ARGV}: now-millis, window-millis, cap, new member. Returns
     * the survivors so Java can Hamming-compare them.
     */
    private static final RedisScript<List> AGE_SNAPSHOT_RECORD_LUA = new DefaultRedisScript<>(
            """
            local now = tonumber(ARGV[1])
            local window = tonumber(ARGV[2])
            local cap = tonumber(ARGV[3])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
            local recent = redis.call('ZRANGE', KEYS[1], 0, -1)
            redis.call('ZADD', KEYS[1], now, ARGV[4])
            redis.call('ZREMRANGEBYRANK', KEYS[1], 0, -(cap + 1))
            redis.call('PEXPIRE', KEYS[1], window)
            return recent
            """,
            List.class);

    private final StringRedisTemplate redis;
    private final BurstProperties properties;

    @Autowired
    public NearDuplicateIndex(StringRedisTemplate redis, BurstProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * Records {@code fingerprint} for {@code emailId} at {@code now} and returns the size of its
     * near-duplicate cluster in the window — itself plus every recent fingerprint within the
     * configured Hamming radius.
     *
     * @return the cluster size, at least 1
     */
    public long recordAndCountCluster(long fingerprint, String emailId, Instant now) {
        String member = Long.toUnsignedString(fingerprint, 16) + MEMBER_DELIMITER + emailId;
        List<?> recent = redis.execute(
                AGE_SNAPSHOT_RECORD_LUA,
                List.of(KEY),
                Long.toString(now.toEpochMilli()),
                Long.toString(properties.window().toMillis()),
                Integer.toString(RECENT_CAP),
                member);

        long cluster = 1; // the email itself
        if (recent != null) {
            int radius = properties.nearDupHammingThreshold();
            for (Object entry : recent) {
                long other = parseFingerprint(entry.toString());
                if (SimHasher.hammingDistance(fingerprint, other) <= radius) {
                    cluster++;
                }
            }
        }
        return cluster;
    }

    private static long parseFingerprint(String member) {
        int delimiter = member.indexOf(MEMBER_DELIMITER);
        String hex = delimiter < 0 ? member : member.substring(0, delimiter);
        return Long.parseUnsignedLong(hex, 16);
    }
}
