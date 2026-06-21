package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * End-to-end proof of lazy read-time decay against a real Postgres (story 03.02).
 * Events are inserted at <em>controlled</em> timestamps (so age is exact, not subject
 * to wall-clock drift) and the read instant is the injected fixed {@link Clock}, so
 * the decayed Beta can be checked against an analytic expectation to tight tolerance.
 *
 * <p>It pins every acceptance criterion: counts scale by {@code 0.5 ^ (Δt / halfLife)}
 * (AC 1); a half-life of dormancy halves the effective evidence and widens the variance
 * back toward the prior (AC 2); the SQL decay matches {@link ExponentialDecay} exactly,
 * which is read/write consistency and path independence (AC 3); and the score changes
 * with the read instant alone, with no scheduled job anywhere in the context (AC 4).
 *
 * <p>The configured half-life is the production {@code 7d} (no override), so this also
 * confirms the default policy value is wired through.
 */
class ReputationDecayIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant READ_AT = Instant.parse("2026-06-21T12:00:00Z");
    private static final Duration HALF_LIFE = Duration.ofDays(7);
    private static final ExponentialDecay DECAY = new ExponentialDecay(HALF_LIFE);

    @TestConfiguration
    static class FixedReadClockConfig {
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(READ_AT, ZoneOffset.UTC);
        }
    }

    @Autowired
    private ReputationService service;

    @Autowired
    private ReputationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ApplicationContext context;

    @Test
    void counts_scale_by_the_half_life_factor_at_read_time() {
        String sender = uniqueSender();
        appendAged(sender, ReputationSignal.GOOD, 8, HALF_LIFE);   // one half-life old
        appendAged(sender, ReputationSignal.BAD, 4, HALF_LIFE.multipliedBy(2)); // two half-lives old

        BetaReputation reputation = service.currentReputation(sender);

        // 8 good aged one half-life -> 4.0; 4 bad aged two half-lives -> 1.0.
        assertThat(reputation.good()).isCloseTo(4.0, within(1e-9));
        assertThat(reputation.bad()).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void one_half_life_of_dormancy_halves_evidence_and_widens_variance() {
        String fresh = uniqueSender();
        appendAged(fresh, ReputationSignal.GOOD, 8, Duration.ZERO);
        appendAged(fresh, ReputationSignal.BAD, 2, Duration.ZERO);

        String dormant = uniqueSender();
        appendAged(dormant, ReputationSignal.GOOD, 8, HALF_LIFE);
        appendAged(dormant, ReputationSignal.BAD, 2, HALF_LIFE);

        BetaReputation freshRep = service.currentReputation(fresh);
        BetaReputation dormantRep = service.currentReputation(dormant);

        // Same 8/2 evidence, but a half-life old: effective count halves (10 -> 5)...
        assertThat(freshRep.count()).isCloseTo(10.0, within(1e-9));
        assertThat(dormantRep.count()).isCloseTo(5.0, within(1e-9));
        // ...so the Beta is less certain (wider) and the mean drifts back toward the
        // 0.5 prior -- stale good behaviour no longer shields as strongly.
        assertThat(dormantRep.variance()).isGreaterThan(freshRep.variance());
        assertThat(Math.abs(dormantRep.mean() - 0.5)).isLessThan(Math.abs(freshRep.mean() - 0.5));
    }

    @Test
    void sql_decay_matches_the_pure_decay_model_exactly() {
        // Locks the SQL aggregation to ExponentialDecay: a single event read at an
        // arbitrary age must equal weight * survivingFraction(age). Because the factor
        // composes multiplicatively, this one-step read is identical to decay folded
        // over any intermediate instants -- read/write consistency and path
        // independence (AC 3, property test).
        String sender = uniqueSender();
        Duration age = Duration.ofDays(10);
        appendAged(sender, ReputationSignal.GOOD, 1, age);

        ReputationCounts counts = repository.countsFor(sender, READ_AT, DECAY);

        assertThat(counts.good()).isCloseTo(DECAY.survivingFraction(age), within(1e-9));
    }

    @Test
    void decay_is_a_pure_read_time_function_with_no_scheduled_job() {
        // The score moves with the read instant alone -- no write, no cron. Read the
        // same un-touched event at two instants a half-life apart and the surviving
        // weight halves, proving decay is computed lazily at read.
        String sender = uniqueSender();
        appendAged(sender, ReputationSignal.GOOD, 1, Duration.ZERO); // occurred at READ_AT

        double atRead = repository.countsFor(sender, READ_AT, DECAY).good();
        double aHalfLifeLater = repository.countsFor(sender, READ_AT.plus(HALF_LIFE), DECAY).good();

        assertThat(atRead).isCloseTo(1.0, within(1e-9));
        assertThat(aHalfLifeLater).isCloseTo(0.5, within(1e-9));

        // And there is genuinely no scheduled decay task in the application context.
        Stream<Object> scheduledTasks = context.getBeansOfType(ScheduledTaskHolder.class).values()
                .stream().flatMap(holder -> holder.getScheduledTasks().stream());
        assertThat(scheduledTasks).isEmpty();
    }

    /**
     * Appends {@code n} identical events for a sender at {@code age} before the fixed
     * read instant, stamping {@code occurred_at} explicitly so the timeline is exact.
     * Inserts directly (the append-only table still accepts INSERTs) to control the
     * timestamp the service's append path would otherwise default to {@code now()}.
     */
    private void appendAged(String sender, ReputationSignal signal, int n, Duration age) {
        OffsetDateTime occurredAt = OffsetDateTime.ofInstant(READ_AT.minus(age), ZoneOffset.UTC);
        for (int i = 0; i < n; i++) {
            jdbc.update("""
                    insert into reputation_events (sender_key, signal, weight, decay_factor, source, occurred_at)
                    values (?, ?, 1.0, 1.0, 'decay-test', ?)
                    """, sender, signal.name(), occurredAt);
        }
    }

    private static String uniqueSender() {
        return "sender-" + UUID.randomUUID() + "@decay-test.example";
    }
}
