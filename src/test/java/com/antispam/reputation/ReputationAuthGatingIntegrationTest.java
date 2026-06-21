package com.antispam.reputation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.antispam.AbstractPostgresIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end proof of soft auth-gated accrual against a real Postgres (story 03.03),
 * exercising the two headline demo beats and the anti-poisoning invariant:
 *
 * <ul>
 *   <li><b>Spoof of a warmed-up domain gets nothing</b> — a domain with strong
 *       authenticated reputation, scored as an unauthenticated email, reads at most
 *       neutral; the earned trust is not inherited.</li>
 *   <li><b>Misconfigured legit sender still accrues</b> — unauthenticated good mail is
 *       recorded (the gate is soft, not a drop), so its bucket evidence rises even while
 *       its trust view is held at neutral; content features then earn real trust
 *       downstream (auth-as-feature, Epic 04).</li>
 * </ul>
 *
 * <p>The clock is pinned <em>before</em> the events so read-time decay (story 03.02) is
 * identically 1.0 and these gating assertions are about the bucket split and the neutral
 * cap alone, not decay. Each test uses a unique sender so the shared container can run
 * them in any order.
 */
class ReputationAuthGatingIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final double NEUTRAL = 0.5; // prior mean for the default 1/1 prior

    @TestConfiguration
    static class FrozenPastClockConfig {
        // Before any event's occurred_at, so the read-time age clamps to zero: decay off.
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
    private ReputationProperties priors;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void a_spoofed_email_does_not_inherit_a_warm_domains_trust() {
        String domain = uniqueSender();
        // Warm the domain with authenticated good mail.
        for (int i = 0; i < 20; i++) {
            service.record(domain, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        }

        // The domain is genuinely warm to an aligned email...
        assertThat(service.reputationFor(domain, true).mean()).isGreaterThan(0.9);
        // ...but a spoofed (unauthenticated) email scores at most neutral: it sees only
        // the empty, neutral-capped unauthenticated bucket, not the earned trust.
        assertThat(service.reputationFor(domain, false).mean()).isLessThanOrEqualTo(NEUTRAL);
    }

    @Test
    void unauthenticated_good_mail_is_capped_at_neutral_and_does_not_move_the_earned_score() {
        String sender = uniqueSender();
        double earnedBefore = service.currentReputation(sender).mean();

        for (int i = 0; i < 30; i++) {
            service.record(sender, ReputationSignal.GOOD, 1.0, "api", ReputationBucket.UNAUTHENTICATED);
        }

        // Lots of unauthenticated good mail: the unauthenticated view is pinned at
        // neutral ("never trusted")...
        assertThat(service.reputationFor(sender, false).mean()).isLessThanOrEqualTo(NEUTRAL);
        assertThat(service.reputationFor(sender, false).mean()).isCloseTo(NEUTRAL, within(1e-9));
        // ...and the authenticated (earned) view is untouched by it (no reverse poisoning).
        assertThat(service.currentReputation(sender).mean()).isEqualTo(earnedBefore);
    }

    @Test
    void unauthenticated_bad_mail_can_still_lower_trust_below_neutral() {
        String sender = uniqueSender();
        for (int i = 0; i < 10; i++) {
            service.record(sender, ReputationSignal.BAD, 1.0, "api", ReputationBucket.UNAUTHENTICATED);
        }

        // The cap is one-sided: unauthenticated mail cannot lift trust, but bad behaviour
        // still drags the unauthenticated view below neutral.
        assertThat(service.reputationFor(sender, false).mean()).isLessThan(NEUTRAL);
    }

    @Test
    void a_misconfigured_legit_sender_keeps_accruing_in_the_unauth_bucket() {
        // The gate is SOFT: unauthenticated good mail is recorded, not dropped. The raw
        // unauthenticated evidence rises with every good message (non-zero accrual slope)
        // even though the capped trust view holds at neutral -- which is what later lets
        // content features (auth-as-feature, Epic 04) earn this sender real trust.
        String sender = uniqueSender();
        double previous = rawUnauthGood(sender);

        for (int i = 0; i < 5; i++) {
            service.record(sender, ReputationSignal.GOOD, 1.0, "feedback", ReputationBucket.UNAUTHENTICATED);
            double now = rawUnauthGood(sender);
            assertThat(now).isGreaterThan(previous);   // strictly increasing evidence
            previous = now;

            // Trust view never penalised below neutral while only good content arrives.
            assertThat(service.reputationFor(sender, false).mean()).isCloseTo(
                    NEUTRAL, within(1e-9));
        }
    }

    @Test
    void bucket_attribution_is_auditable_in_the_event_log() {
        String sender = uniqueSender();
        service.record(sender, ReputationSignal.GOOD, 1.0, "decision", ReputationBucket.AUTHENTICATED);
        service.record(sender, ReputationSignal.BAD, 0.5, "api", ReputationBucket.UNAUTHENTICATED);

        List<Map<String, Object>> rows = jdbc.queryForList(
                "select bucket, signal, weight from reputation_events where sender_key = ? order by id", sender);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("bucket", "AUTHENTICATED").containsEntry("signal", "GOOD");
        assertThat(((Number) rows.get(0).get("weight")).doubleValue()).isEqualTo(1.0);
        assertThat(rows.get(1)).containsEntry("bucket", "UNAUTHENTICATED").containsEntry("signal", "BAD");
        assertThat(((Number) rows.get(1).get("weight")).doubleValue()).isEqualTo(0.5);
    }

    /** The raw (pre-cap) decayed good weight in the unauthenticated bucket, read now. */
    private double rawUnauthGood(String sender) {
        return repository.countsFor(sender, clock.instant(), priors.decay()).unauthenticated().good();
    }

    private static String uniqueSender() {
        return "sender-" + UUID.randomUUID() + "@authgate-test.example";
    }
}
