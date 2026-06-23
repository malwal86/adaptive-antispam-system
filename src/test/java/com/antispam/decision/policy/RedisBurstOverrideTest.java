package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * The burst detector's decision logic, unit-level (stories 06.01, 06.02): how the sender-velocity and
 * content near-duplicate counts map to an escalation against the active policy's threshold, that the
 * threshold is sourced from the policy (not a constant), that the override carries the configured tier
 * and the {@code BURST_OVERRIDE} reason with the firing trigger, and the degrade-open-on-outage
 * contract. The windows themselves run in Lua and are pinned end-to-end against real Redis in
 * {@link BurstOverrideIntegrationTest} and {@link NearDuplicateIntegrationTest} — here the counters
 * are stubbed so the surrounding Java logic is tested in isolation.
 */
@ExtendWith(MockitoExtension.class)
class RedisBurstOverrideTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-22T12:00:00Z"), ZoneOffset.UTC);
    private static final BurstProperties PROPS =
            new BurstProperties(true, Duration.ofSeconds(60), Decision.QUARANTINE, 6);
    private static final Policy POLICY = new Policy(
            "burst-test-v1", true, 0.50, 0.80, 0.95, 0.40, 0.05, 5, "bootstrap-v1", Instant.EPOCH);

    @Mock
    private SlidingWindowCounter senderWindow;

    @Mock
    private NearDuplicateIndex nearDuplicateIndex;

    @Mock
    private BurstMeter meter;

    private final SimHasher simHasher = new SimHasher();

    private RedisBurstOverride detector() {
        return new RedisBurstOverride(
                senderWindow, nearDuplicateIndex, simHasher, PROPS, meter, FIXED);
    }

    /** An email with an empty body (no content fingerprint), so only sender velocity is in play. */
    private static Email emailFrom(String sender) {
        return email(sender, new byte[0]);
    }

    private static Email withBody(String sender, String body) {
        // A minimal MIME envelope so EmailFeatureExtractor.displayText decodes the body (a bare
        // string with no header/blank-line boundary parses as all-headers, i.e. an empty body).
        String raw = "Subject: test\r\n\r\n" + body;
        return email(sender, raw.getBytes(StandardCharsets.UTF_8));
    }

    private static Email email(String sender, byte[] raw) {
        ParsedEmail metadata = new ParsedEmail(sender, null, null, null, null, null);
        return new Email(UUID.randomUUID(), new byte[32], raw, metadata, "test", Instant.now());
    }

    @Test
    void does_not_escalate_when_the_count_is_at_or_below_the_policy_threshold() {
        // Count exactly equals the threshold (5): "exceeds" is strict, so this is not yet a burst.
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(5L);

        Optional<BurstOverride.Escalation> escalation =
                detector().evaluate(emailFrom("steady@example.com"), POLICY);

        assertThat(escalation).isEmpty();
        verifyNoInteractions(meter);
    }

    @Test
    void escalates_on_sender_velocity_with_the_burst_reason_when_the_count_exceeds_the_threshold() {
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(6L);

        Optional<BurstOverride.Escalation> escalation =
                detector().evaluate(emailFrom("blast@example.com"), POLICY);

        assertThat(escalation).isPresent();
        assertThat(escalation.get().tier()).isEqualTo(Decision.QUARANTINE);
        assertThat(escalation.get().reason()).isEqualTo(ReasonCode.BURST_OVERRIDE);
        verify(meter).recordBurst(Decision.QUARANTINE, BurstTrigger.SENDER_VELOCITY);
    }

    @Test
    void escalates_on_a_content_near_dup_cluster_even_when_sender_velocity_is_low() {
        // One message from this sender (sender count 1 <= 5), but its content is part of a near-dup
        // cluster of 8 > 5 — a templated campaign spread across senders.
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(1L);
        when(nearDuplicateIndex.recordAndCountCluster(anyLong(), anyString(), any())).thenReturn(8L);

        Optional<BurstOverride.Escalation> escalation =
                detector().evaluate(withBody("oneoff@example.com", "Verify your account now to avoid closure"),
                        POLICY);

        assertThat(escalation).isPresent();
        assertThat(escalation.get().reason()).isEqualTo(ReasonCode.BURST_OVERRIDE);
        verify(meter).recordBurst(Decision.QUARANTINE, BurstTrigger.CONTENT_NEAR_DUP);
    }

    @Test
    void does_not_consult_the_near_dup_index_for_a_token_free_body() {
        // Empty body → fingerprint 0 → no content cluster to look up (blank bodies are never grouped).
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(1L);

        assertThat(detector().evaluate(emailFrom("blank@example.com"), POLICY)).isEmpty();

        verifyNoInteractions(nearDuplicateIndex);
    }

    @Test
    void sources_the_threshold_from_the_active_policy_not_a_constant() {
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(6L);
        // A laxer policy whose threshold (10) the same count (6) does not exceed.
        Policy lax = new Policy(
                "lax-v1", true, 0.50, 0.80, 0.95, 0.40, 0.05, 10, "bootstrap-v1", Instant.EPOCH);

        assertThat(detector().evaluate(emailFrom("s@example.com"), lax)).isEmpty();
        // The same count against the stricter POLICY (threshold 5) does escalate.
        assertThat(detector().evaluate(emailFrom("s@example.com"), POLICY)).isPresent();
    }

    @Test
    void keys_the_sender_window_per_sender_and_records_the_email_id_at_the_clock_instant() {
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(1L);
        Email email = emailFrom("Sender@Example.com");

        detector().evaluate(email, POLICY);

        // Sender-namespaced key (normalized lower-case), the email id as the unique member, the
        // clock's instant, and the configured window — so distinct messages each add a count.
        verify(senderWindow).recordAndCount(
                eq(RedisBurstOverride.KEY_PREFIX + "sender@example.com"),
                eq(email.id().toString()),
                eq(FIXED.instant()),
                eq(Duration.ofSeconds(60)));
    }

    @Test
    void degrades_open_when_the_window_check_fails_against_redis() {
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        // A Redis outage must never mass-escalate mail: treat it as "no burst", posterior stands.
        Optional<BurstOverride.Escalation> escalation =
                detector().evaluate(emailFrom("s@example.com"), POLICY);

        assertThat(escalation).isEmpty();
        verify(meter, never()).recordBurst(any(), any());
    }

    @Test
    void degrades_open_when_the_near_dup_index_fails_against_redis() {
        when(senderWindow.recordAndCount(anyString(), anyString(), any(), any())).thenReturn(1L);
        when(nearDuplicateIndex.recordAndCountCluster(anyLong(), anyString(), any()))
                .thenThrow(new RedisConnectionFailureException("down"));

        Optional<BurstOverride.Escalation> escalation =
                detector().evaluate(withBody("s@example.com", "Verify your account now"), POLICY);

        assertThat(escalation).isEmpty();
        verify(meter, never()).recordBurst(any(), any());
    }
}
