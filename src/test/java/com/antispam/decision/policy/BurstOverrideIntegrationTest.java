package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.model.OnnxModel;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
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
 * End-to-end proof of the runtime burst override (story 06.01) against real Redis. The whole point
 * is the sliding window — steady traffic stays below the threshold, a blast crosses it, and counts
 * age out once the window passes — none of which a mocked counter can prove. A settable clock lets
 * the window "advance" without sleeping, and a small policy threshold (3) keeps the blasts tiny.
 *
 * <p>It pins the acceptance criteria that need a real window: normal-rate traffic never escalates
 * (AC 1, the false-positive guard); a blast exceeding the threshold escalates regardless of
 * posterior with the burst reason (AC 2); counts age out as time advances past the window so a past
 * blast does not escalate forever (AC 3); and the threshold comes from the active policy row (AC 4).
 * The tier-precedence into {@code PolicyDecisionService} is covered in {@code PolicyDecisionServiceTest}.
 */
@TestPropertySource(properties = {
        "antispam.burst.enabled=true",
        "antispam.burst.window=60s",
        "antispam.burst.escalate-to=QUARANTINE",
})
class BurstOverrideIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-22T12:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int THRESHOLD = 3;
    private static Instant now = BASE;

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
                    return now;
                }
            };
        }
    }

    @Autowired
    private BurstOverride burstOverride;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private StringRedisTemplate redis;

    private Policy policy;

    @BeforeEach
    void seedPolicyAndResetRedis() {
        now = BASE;
        redis.getRequiredConnectionFactory().getConnection().serverCommands().flushDb();
        policies.save(new Policy("burst-it-v1", false, 0.50, 0.80, 0.95, 0.40, 0.05,
                THRESHOLD, OnnxModel.MODEL_VERSION, Instant.EPOCH));
        policies.activate("burst-it-v1");
        policy = policies.findActive().orElseThrow();
    }

    @AfterEach
    void restoreSharedActivePolicy() {
        // Integration tests share one Postgres container, so restore the seeded active regime that
        // other suites assume rather than leaving burst-it-v1 enforcing.
        policies.activate("bootstrap-v1");
    }

    @Test
    void steady_traffic_below_the_threshold_never_escalates() {
        // THRESHOLD distinct messages from one sender within the window: count reaches 3, never > 3.
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(evaluate("steady@example.com")).isEmpty();
        }
    }

    @Test
    void a_blast_exceeding_the_threshold_escalates_with_the_burst_reason() {
        // First THRESHOLD messages stay at/under the cap...
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(evaluate("blast@example.com")).isEmpty();
        }
        // ...the next one pushes the count to 4 > 3 and escalates regardless of posterior.
        Optional<BurstOverride.Escalation> escalation = evaluate("blast@example.com");

        assertThat(escalation).isPresent();
        assertThat(escalation.get().tier()).isEqualTo(Decision.QUARANTINE);
        assertThat(escalation.get().reason()).isEqualTo(ReasonCode.BURST_OVERRIDE);
    }

    @Test
    void counts_age_out_once_the_window_passes_so_a_past_blast_does_not_escalate_forever() {
        // A blast at BASE escalates the 4th message.
        for (int i = 0; i < THRESHOLD; i++) {
            evaluate("waves@example.com");
        }
        assertThat(evaluate("waves@example.com")).isPresent();

        // Advance past the window: every earlier hit is now older than the window and ages out, so
        // the next message starts a fresh count of 1 — no permanent escalation after the blast ends.
        now = BASE.plus(WINDOW).plusSeconds(1);
        assertThat(evaluate("waves@example.com")).isEmpty();
    }

    @Test
    void distinct_senders_do_not_share_a_window() {
        // Two senders each sending up to the threshold must not pool into one another's count.
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(evaluate("alice@example.com")).isEmpty();
            assertThat(evaluate("bob@example.com")).isEmpty();
        }
    }

    private Optional<BurstOverride.Escalation> evaluate(String sender) {
        ParsedEmail metadata = new ParsedEmail(sender, null, null, null, null, null);
        Email email =
                new Email(UUID.randomUUID(), new byte[32], new byte[0], metadata, "test", Instant.now());
        return burstOverride.evaluate(email, policy);
    }
}
