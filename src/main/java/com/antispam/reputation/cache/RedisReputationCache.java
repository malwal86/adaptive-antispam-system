package com.antispam.reputation.cache;

import com.antispam.reputation.BucketedReputationCounts;
import com.antispam.reputation.CachedReputation;
import com.antispam.reputation.ReputationCounts;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Redis-backed reputation read cache (story 03.04): each sender's folded
 * {@link CachedReputation} snapshot lives in a Redis hash so the {@code <100ms} read
 * path is served without replaying Postgres. Active only when
 * {@code antispam.reputation.cache.enabled=true}; otherwise {@link NoOpReputationCache}
 * is wired in.
 *
 * <p><b>Derived, resilient.</b> Postgres stays the source of truth, so this cache never
 * has to be authoritative or even available. Every Redis interaction is wrapped: a
 * connection failure (or any client error) on a read is reported as a miss and on a
 * write is dropped, so the service transparently falls back to Postgres-backed
 * computation rather than returning wrong/empty reputation (AC 4).
 *
 * <p>The snapshot is stored as a hash of the four bucket counts plus the fold instant
 * (epoch millis). A hash — rather than an opaque blob — is also what lets story 03.05
 * make updates lock-free with atomic field operations.
 */
@Component
@ConditionalOnProperty(name = "antispam.reputation.cache.enabled", havingValue = "true")
public class RedisReputationCache implements ReputationReadCache {

    private static final Logger log = LoggerFactory.getLogger(RedisReputationCache.class);

    // Versioned key prefix so the cache layout can evolve without colliding with old
    // entries (a bumped version simply misses and rebuilds from events).
    private static final String KEY_PREFIX = "reputation:v1:";
    private static final String AUTH_GOOD = "ag";
    private static final String AUTH_BAD = "ab";
    private static final String UNAUTH_GOOD = "ug";
    private static final String UNAUTH_BAD = "ub";
    private static final String FOLDED_AT_MS = "ts";

    private final StringRedisTemplate redis;

    @Autowired
    public RedisReputationCache(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<CachedReputation> get(String senderKey) {
        try {
            Map<Object, Object> raw = redis.opsForHash().entries(KEY_PREFIX + senderKey);
            return fromHash(raw);
        } catch (RuntimeException e) {
            // Redis down / unreachable: report a miss so the caller rebuilds from Postgres.
            log.warn("reputation cache read failed for sender={}, falling back to Postgres: {}",
                    senderKey, e.toString());
            return Optional.empty();
        }
    }

    @Override
    public void put(String senderKey, CachedReputation entry) {
        try {
            redis.opsForHash().putAll(KEY_PREFIX + senderKey, toHash(entry));
        } catch (RuntimeException e) {
            // Best-effort: a failed write just means the next read rebuilds from Postgres.
            log.warn("reputation cache write failed for sender={}, leaving it to rebuild: {}",
                    senderKey, e.toString());
        }
    }

    /** Serializes a snapshot to its Redis hash fields. Package-private for round-trip testing. */
    static Map<String, String> toHash(CachedReputation entry) {
        BucketedReputationCounts counts = entry.foldedCounts();
        Map<String, String> hash = new HashMap<>();
        hash.put(AUTH_GOOD, Double.toString(counts.authenticated().good()));
        hash.put(AUTH_BAD, Double.toString(counts.authenticated().bad()));
        hash.put(UNAUTH_GOOD, Double.toString(counts.unauthenticated().good()));
        hash.put(UNAUTH_BAD, Double.toString(counts.unauthenticated().bad()));
        hash.put(FOLDED_AT_MS, Long.toString(entry.foldedAt().toEpochMilli()));
        return hash;
    }

    /**
     * Rebuilds a snapshot from its Redis hash, or empty when the key is absent (no
     * entries) or any required field is missing/unparseable — all treated as a miss.
     */
    static Optional<CachedReputation> fromHash(Map<Object, Object> raw) {
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            BucketedReputationCounts counts = new BucketedReputationCounts(
                    new ReputationCounts(field(raw, AUTH_GOOD), field(raw, AUTH_BAD)),
                    new ReputationCounts(field(raw, UNAUTH_GOOD), field(raw, UNAUTH_BAD)));
            Instant foldedAt = Instant.ofEpochMilli((long) field(raw, FOLDED_AT_MS));
            return Optional.of(new CachedReputation(counts, foldedAt));
        } catch (NullPointerException | NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static double field(Map<Object, Object> raw, String name) {
        Object value = raw.get(name);
        if (value == null) {
            throw new NullPointerException("missing cache field: " + name);
        }
        return Double.parseDouble(value.toString());
    }
}
