package com.antispam.reputation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import com.antispam.reputation.BucketedReputationCounts;
import com.antispam.reputation.CachedReputation;
import com.antispam.reputation.ReputationCounts;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis cache's serialization and its resilience contract (story 03.04), unit-level:
 * a snapshot survives a hash round-trip, a missing/partial key reads as a miss, and —
 * crucially — a Redis outage is swallowed so reads report a miss and writes are dropped
 * rather than propagating, which is what makes the service fall back to Postgres (AC 4).
 */
@ExtendWith(MockitoExtension.class)
class RedisReputationCacheTest {

    private static final CachedReputation ENTRY = new CachedReputation(
            new BucketedReputationCounts(new ReputationCounts(8, 2), new ReputationCounts(3.5, 1.25)),
            Instant.ofEpochMilli(1_750_000_000_000L));

    @Mock
    private StringRedisTemplate redis;

    @Mock
    private HashOperations<String, Object, Object> hashOps;

    @Test
    void a_snapshot_survives_the_hash_round_trip() {
        Map<Object, Object> stored = new HashMap<>(RedisReputationCache.toHash(ENTRY));

        Optional<CachedReputation> restored = RedisReputationCache.fromHash(stored);

        assertThat(restored).contains(ENTRY);
    }

    @Test
    void an_absent_key_reads_as_a_miss() {
        assertThat(RedisReputationCache.fromHash(Map.of())).isEmpty();
    }

    @Test
    void a_partial_hash_reads_as_a_miss() {
        Map<Object, Object> partial = new HashMap<>();
        partial.put("ag", "8.0"); // missing the other fields
        assertThat(RedisReputationCache.fromHash(partial)).isEmpty();
    }

    @Test
    void a_redis_outage_on_read_reports_a_miss() {
        when(redis.opsForHash()).thenReturn(hashOps);
        when(hashOps.entries(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        Optional<CachedReputation> result = new RedisReputationCache(redis).get("alice@example.com");

        assertThat(result).isEmpty();
    }

    @Test
    void a_redis_outage_on_write_is_swallowed() {
        when(redis.opsForHash()).thenReturn(hashOps);
        doThrow(new RedisConnectionFailureException("down")).when(hashOps).putAll(anyString(), any());

        assertThatCode(() -> new RedisReputationCache(redis).put("alice@example.com", ENTRY))
                .doesNotThrowAnyException();
    }
}
