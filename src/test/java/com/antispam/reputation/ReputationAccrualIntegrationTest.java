package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of Beta reputation over the append-only log against a real
 * Postgres (story 03.01). It pins the properties the story promises: a new sender
 * reads the wide prior, a good/bad sequence moves the mean and shrinks the variance
 * per the formula, the cached score equals the recompute-from-events, changes are
 * appends (the table rejects in-place mutation), and counts are order-independent.
 *
 * <p><b>Decay is held out here.</b> The clock is pinned to an instant <em>before</em>
 * any event is recorded, so every event's age is negative and clamps to zero — read-
 * time decay (story 03.02) is identically 1.0 and these accrual assertions stay exact.
 * Decay's own behaviour is covered by {@link ReputationDecayIntegrationTest}.
 *
 * <p>Each test uses a unique sender key so the shared container/context can run them
 * in any order without cross-talk.
 */
class ReputationAccrualIntegrationTest extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FrozenPastClockConfig {
        // Before any event's occurred_at (which the DB stamps at the real now), so the
        // read-time age clamps to zero and no evidence has decayed yet.
        @Bean
        @Primary
        Clock clock() {
            return Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private ReputationService service;

    @Autowired
    private ReputationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_new_sender_reads_the_high_variance_prior() {
        BetaReputation reputation = service.currentReputation(uniqueSender());

        assertThat(reputation.mean()).isEqualTo(0.5);
        assertThat(reputation.variance()).isCloseTo(1.0 / 12.0, within(1e-12));
        assertThat(reputation.count()).isZero();
    }

    @Test
    void a_good_bad_sequence_updates_the_mean_and_variance_per_the_formula() {
        String sender = uniqueSender();
        for (int i = 0; i < 8; i++) {
            service.record(sender, ReputationSignal.GOOD, 1.0, "api");
        }
        for (int i = 0; i < 2; i++) {
            service.record(sender, ReputationSignal.BAD, 1.0, "api");
        }

        BetaReputation reputation = service.currentReputation(sender);
        // 8 good / 2 bad, prior 1/1: mean 9/12, variance 27/(144*13).
        assertThat(reputation.count()).isEqualTo(10.0);
        assertThat(reputation.mean()).isCloseTo(0.75, within(1e-12));
        assertThat(reputation.variance()).isCloseTo(27.0 / 1872.0, within(1e-12));
    }

    @Test
    void variance_shrinks_as_more_evidence_arrives() {
        String sender = uniqueSender();

        double afterOne = service.record(sender, ReputationSignal.GOOD, 1.0, "api").variance();
        double afterFive = afterOne;
        for (int i = 0; i < 4; i++) {
            afterFive = service.record(sender, ReputationSignal.GOOD, 1.0, "api").variance();
        }
        double afterTwenty = afterFive;
        for (int i = 0; i < 15; i++) {
            afterTwenty = service.record(sender, ReputationSignal.GOOD, 1.0, "api").variance();
        }

        assertThat(afterFive).isLessThan(afterOne);
        assertThat(afterTwenty).isLessThan(afterFive);
    }

    @Test
    void the_cached_score_equals_the_recompute_from_events() {
        String sender = uniqueSender();
        service.record(sender, ReputationSignal.GOOD, 1.0, "api");
        service.record(sender, ReputationSignal.BAD, 1.0, "api");
        service.record(sender, ReputationSignal.GOOD, 1.0, "api");

        double recomputed = service.currentReputation(sender).mean();
        double cached = repository.findCachedScore(sender).orElseThrow();

        // The senders cache is a faithful projection of the log: rebuilding from
        // events reproduces exactly the cached value (AC 4 / AC 5).
        assertThat(cached).isCloseTo(recomputed, within(1e-12));
    }

    @Test
    void every_signal_is_an_append_never_an_in_place_mutation() {
        String sender = uniqueSender();

        service.record(sender, ReputationSignal.GOOD, 1.0, "api");
        service.record(sender, ReputationSignal.BAD, 1.0, "api");

        assertThat(eventRowCount(sender)).isEqualTo(2);
        // The log is append-only at the database: rewriting or deleting history is
        // rejected, so the audit/rebuild guarantees cannot be quietly broken.
        assertThatThrownBy(() -> jdbc.update(
                "update reputation_events set weight = 99 where sender_key = ?", sender))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "delete from reputation_events where sender_key = ?", sender))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void counts_are_order_independent() {
        String forward = uniqueSender();
        service.record(forward, ReputationSignal.GOOD, 1.0, "api");
        service.record(forward, ReputationSignal.GOOD, 1.0, "api");
        service.record(forward, ReputationSignal.BAD, 1.0, "api");

        String reversed = uniqueSender();
        service.record(reversed, ReputationSignal.BAD, 1.0, "api");
        service.record(reversed, ReputationSignal.GOOD, 1.0, "api");
        service.record(reversed, ReputationSignal.GOOD, 1.0, "api");

        // Same multiset of signals, different arrival order: identical Beta (the
        // weighted sum is commutative pre-decay).
        assertThat(service.currentReputation(forward).mean())
                .isEqualTo(service.currentReputation(reversed).mean());
    }

    private int eventRowCount(String sender) {
        return jdbc.queryForObject(
                "select count(*) from reputation_events where sender_key = ?", Integer.class, sender);
    }

    /** A sender key unique to one test, so tests don't share reputation state. */
    private static String uniqueSender() {
        return "sender-" + UUID.randomUUID() + "@rep-test.example";
    }
}
