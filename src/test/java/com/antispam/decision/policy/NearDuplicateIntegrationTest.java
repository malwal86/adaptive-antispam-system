package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.model.OnnxModel;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
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
 * End-to-end proof of runtime near-duplicate detection (story 06.02) against real Redis: the cheap
 * surface tier that catches templated blasts. The key claims a mocked index cannot make are that a
 * blast of near-duplicate content escalates <em>across distinct senders</em> (so it is content, not
 * per-sender velocity, that fires) and that trivial template variation still clusters — both against
 * the real SimHash + Lua window. A settable clock advances the window without sleeping; a small policy
 * threshold (3) keeps the blasts tiny.
 *
 * <p>Pins the acceptance criteria that need the real index: a templated blast is grouped under one
 * runtime cluster feeding the burst override (AC 3); trivially-varied members land in that cluster
 * (AC 1); distinct ham from many senders is not grouped (AC 2); and fingerprints are retained only
 * for the bounded window (AC 5 — a past blast ages out).
 */
@TestPropertySource(properties = {
        "antispam.burst.enabled=true",
        "antispam.burst.window=60s",
        "antispam.burst.escalate-to=QUARANTINE",
        "antispam.burst.near-dup-hamming-threshold=6",
})
class NearDuplicateIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final Instant BASE = Instant.parse("2026-06-22T12:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int THRESHOLD = 3;
    private static Instant now = BASE;

    // A paragraph-length template (SimHash is noisier on short text) and a trivially-varied copy that
    // changes only the salutation — the kind of cosmetic tweak a template blast uses to dodge exact
    // dedup. Their fingerprints are a few bits apart, inside the 6-bit near-dup radius.
    private static final String TEMPLATE = """
            Dear customer, your account has been temporarily suspended due to unusual activity.
            Please verify your identity within 24 hours by clicking the secure link below to avoid
            permanent closure. Our support team is available around the clock to assist you with any
            questions about restoring full access to your account and protecting your information.
            """;
    private static final String VARIED = TEMPLATE.replace("Dear customer", "Dear   valued user");

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
        if (policies.findByVersion("near-dup-it-v1").isEmpty()) {
            policies.save(new Policy("near-dup-it-v1", false, 0.50, 0.80, 0.95, 0.40, 0.05,
                    THRESHOLD, OnnxModel.MODEL_VERSION, Instant.EPOCH));
        }
        policies.activate("near-dup-it-v1");
        policy = policies.findActive().orElseThrow();
    }

    @AfterEach
    void restoreSharedActivePolicy() {
        policies.activate("bootstrap-v1");
    }

    @Test
    void a_templated_blast_across_distinct_senders_escalates_on_content_near_duplication() {
        // Identical template body, a different sender each time, so per-sender velocity stays at 1 —
        // only the content cluster can fire. The 4th message pushes the cluster to 4 > 3.
        for (int i = 0; i < THRESHOLD; i++) {
            assertThat(evaluate("sender" + i + "@example.com", TEMPLATE)).isEmpty();
        }
        Optional<BurstOverride.Escalation> escalation = evaluate("sender-last@example.com", TEMPLATE);

        assertThat(escalation).isPresent();
        assertThat(escalation.get().reason()).isEqualTo(ReasonCode.BURST_OVERRIDE);
    }

    @Test
    void trivially_varied_members_land_in_the_same_cluster_as_the_template() {
        // Base template, then trivially-varied copies (whitespace + a swapped salutation). The variant
        // is within the near-dup radius of the base, so they cluster despite differing exact hashes.
        assertThat(evaluate("a@example.com", TEMPLATE)).isEmpty();   // cluster 1
        assertThat(evaluate("b@example.com", VARIED)).isEmpty();     // cluster 2 (varied ~ base)
        assertThat(evaluate("c@example.com", VARIED)).isEmpty();     // cluster 3
        Optional<BurstOverride.Escalation> escalation = evaluate("d@example.com", VARIED); // cluster 4

        assertThat(escalation).isPresent();
        assertThat(escalation.get().reason()).isEqualTo(ReasonCode.BURST_OVERRIDE);
    }

    @Test
    void distinct_ham_from_many_senders_is_not_grouped() {
        // Each message is unrelated content from a different sender: cluster size 1, velocity 1.
        String[] distinct = {
                "Lunch is moving to 1pm today, the second-floor room was double-booked again.",
                "The quarterly numbers look strong; revenue is up and churn is down across regions.",
                "Reminder: the office will be closed Monday for the public holiday, back Tuesday.",
                "Can you review the draft proposal before Thursday and send any edits my way?",
        };
        for (int i = 0; i < distinct.length; i++) {
            assertThat(evaluate("person" + i + "@example.com", distinct[i])).isEmpty();
        }
    }

    @Test
    void a_past_blast_ages_out_of_the_window() {
        for (int i = 0; i < THRESHOLD; i++) {
            evaluate("s" + i + "@example.com", TEMPLATE);
        }
        assertThat(evaluate("s-last@example.com", TEMPLATE)).isPresent();

        // Advance past the window: the earlier fingerprints age out, so a fresh near-dup starts a
        // cluster of 1 — no permanent escalation after the campaign ends.
        now = BASE.plus(WINDOW).plusSeconds(1);
        assertThat(evaluate("s-after@example.com", TEMPLATE)).isEmpty();
    }

    private Optional<BurstOverride.Escalation> evaluate(String sender, String body) {
        // A minimal MIME envelope so EmailFeatureExtractor.displayText decodes the body.
        byte[] raw = ("Subject: test\r\n\r\n" + body).getBytes(StandardCharsets.UTF_8);
        ParsedEmail metadata = new ParsedEmail(sender, null, null, null, null, null);
        Email email = new Email(UUID.randomUUID(), new byte[32], raw, metadata, "test", Instant.now());
        return burstOverride.evaluate(email, policy);
    }
}
