package com.antispam.reputation.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.reputation.BucketedReputationCounts;
import com.antispam.reputation.CachedReputation;
import com.antispam.reputation.GatedReputation;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationCounts;
import com.antispam.reputation.ReputationProperties;
import com.antispam.reputation.ReputationRepository;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * End-to-end proof of the Redis materialized cache against real Redis + Postgres
 * (story 03.04). The cache is enabled here (it is off by default), a Redis container
 * backs {@code spring.data.redis}, and a settable clock lets a read happen "later" than
 * a write so decay-through-cache is exercised deterministically.
 *
 * <p>It pins every acceptance criterion: a write is served from Redis on later reads
 * (AC 1); a fully flushed cache rebuilds from {@code reputation_events} with the
 * identical score (AC 2); the full rebuild job reconstructs every sender (AC 3); and a
 * cached read aged forward equals a direct Postgres replay at the same instant — the
 * parity that lets the cache serve correct, decayed reads without touching Postgres
 * (AC 5). The Redis-outage fallback (AC 4) is pinned at the unit level in
 * {@link RedisReputationCacheTest}, since the container here is deliberately healthy.
 */
@TestPropertySource(properties = "antispam.reputation.cache.enabled=true")
class ReputationCacheIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-21T12:00:00Z");
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
        // Set the URL (not host/port): the test profile supplies a placeholder
        // spring.data.redis.url, and a URL takes precedence over host/port — so this
        // must override that same key to actually point at the container.
        registry.add("spring.data.redis.url",
                () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class SettableClockConfig {
        // A clock the tests can advance, so a read can be "later" than a write.
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
    private ReputationService service;

    @Autowired
    private ReputationRepository repository;

    @Autowired
    private ReputationProperties priors;

    @Autowired
    private ReputationReadCache cache;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbc;

    @org.junit.jupiter.api.BeforeEach
    void resetClock() {
        NOW.set(BASE);
    }

    @Test
    void a_write_populates_redis_and_later_reads_are_served_from_it() {
        String sender = uniqueSender();
        service.record(sender, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);

        // The write populated the cache...
        assertThat(cache.get(sender)).isPresent();
        assertThat(service.currentReputation(sender).mean()).isGreaterThan(0.5);

        // ...and reads come from Redis, not Postgres: overwrite the entry with a sentinel
        // and the read reflects it (if reads hit Postgres this would be ignored).
        cache.put(sender, new CachedReputation(
                new BucketedReputationCounts(new ReputationCounts(0, 50), new ReputationCounts(0, 0)),
                NOW.get()));
        assertThat(service.currentReputation(sender).mean()).isLessThan(0.1);
    }

    @Test
    void a_flushed_cache_rebuilds_from_events_with_the_identical_score() {
        String sender = uniqueSender();
        for (int i = 0; i < 6; i++) {
            service.record(sender, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        }
        service.record(sender, ReputationSignal.BAD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        double before = service.currentReputation(sender).mean();

        flushRedis();
        assertThat(cache.get(sender)).isEmpty(); // genuinely wiped

        double afterRebuild = service.currentReputation(sender).mean();

        assertThat(afterRebuild).isCloseTo(before, within(1e-9)); // rebuilt from events
        assertThat(cache.get(sender)).isPresent();                // and re-populated
    }

    @Test
    void the_full_rebuild_job_reconstructs_every_sender() {
        String alice = uniqueSender();
        String bob = uniqueSender();
        service.record(alice, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        service.record(bob, ReputationSignal.BAD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        double aliceBefore = service.currentReputation(alice).mean();
        double bobBefore = service.currentReputation(bob).mean();

        flushRedis();
        int rebuilt = service.rebuildCacheFromEvents();

        assertThat(rebuilt).isGreaterThanOrEqualTo(2);
        assertThat(cache.get(alice)).isPresent();
        assertThat(cache.get(bob)).isPresent();
        assertThat(service.currentReputation(alice).mean()).isCloseTo(aliceBefore, within(1e-9));
        assertThat(service.currentReputation(bob).mean()).isCloseTo(bobBefore, within(1e-9));
    }

    @Test
    void a_cached_read_aged_forward_equals_a_direct_postgres_replay() {
        // Events with explicit past timestamps so the timeline is exact, then warm the
        // cache at BASE and read again a half-life later: the cached snapshot aged forward
        // must equal a fresh Postgres replay at that instant (composability of decay).
        String sender = uniqueSender();
        insertAged(sender, ReputationSignal.GOOD, 8, Duration.ofDays(2));
        insertAged(sender, ReputationSignal.BAD, 2, Duration.ofDays(2));

        service.currentReputation(sender); // miss -> populates the snapshot folded at BASE

        Instant later = BASE.plus(Duration.ofDays(7));
        NOW.set(later);

        double fromCache = service.currentReputation(sender).mean();
        double fromPostgres = GatedReputation.from(
                repository.countsFor(sender, later, priors.decay()), priors).authenticated().mean();

        assertThat(fromCache).isCloseTo(fromPostgres, within(1e-9));
    }

    private void flushRedis() {
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
    }

    private void insertAged(String sender, ReputationSignal signal, int n, Duration age) {
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(BASE.minus(age), ZoneOffset.UTC);
        for (int i = 0; i < n; i++) {
            jdbc.update("""
                    insert into reputation_events (sender_key, signal, weight, decay_factor, source, bucket, occurred_at)
                    values (?, ?, 1.0, 1.0, 'cache-test', 'AUTHENTICATED', ?)
                    """, sender, signal.name(), occurredAt);
        }
    }

    private static String uniqueSender() {
        return "sender-" + UUID.randomUUID() + "@cache-test.example";
    }
}
