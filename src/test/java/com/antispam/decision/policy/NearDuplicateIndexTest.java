package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The near-duplicate index's cluster-counting logic, unit-level (story 06.02): given the recent
 * fingerprints the Lua snapshot returns, it counts how many fall within the configured Hamming radius
 * of the new fingerprint (plus the email itself), and passes the window/cap/member the script reads.
 * The Lua maintenance against real Redis is pinned in {@link NearDuplicateIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class NearDuplicateIndexTest {

    private static final BurstProperties PROPS =
            new BurstProperties(true, Duration.ofSeconds(60), Decision.QUARANTINE, 6);
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private static final String TEMPLATE = """
            Dear customer, your account has been temporarily suspended due to unusual activity.
            Please verify your identity within 24 hours by clicking the secure link below to avoid
            permanent closure. Our support team is available around the clock to assist you with any
            questions about restoring full access to your account and protecting your information.
            """;

    private final SimHasher hasher = new SimHasher();

    @Mock
    private StringRedisTemplate redis;

    private NearDuplicateIndex index() {
        return new NearDuplicateIndex(redis, PROPS);
    }

    private String member(long fingerprint, String id) {
        return Long.toUnsignedString(fingerprint, 16) + ":" + id;
    }

    @Test
    @SuppressWarnings("unchecked")
    void counts_recent_fingerprints_within_the_hamming_radius_plus_itself() {
        long fp = hasher.fingerprint(TEMPLATE);
        long near = hasher.fingerprint(TEMPLATE.replace("Dear customer", "Dear   valued user")); // ~4 bits
        long far = hasher.fingerprint("""
                Hi team, attached are the notes from this morning's planning sync about the analytics
                dashboard, the billing migration, and the onboarding flow redesign for next quarter.
                """); // unrelated, >6 bits

        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of(member(near, "a"), member(far, "b")));

        long cluster = index().recordAndCountCluster(fp, "self", NOW);

        // self (1) + near (within 6) = 2; the unrelated fingerprint is excluded.
        assertThat(cluster).isEqualTo(2L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void counts_only_itself_when_there_are_no_recent_fingerprints() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(List.of());

        assertThat(index().recordAndCountCluster(hasher.fingerprint(TEMPLATE), "self", NOW)).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void passes_the_window_cap_and_member_the_script_reads() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(null);
        long fp = hasher.fingerprint(TEMPLATE);

        // A null reply degrades to a count of itself only — never throws.
        assertThat(index().recordAndCountCluster(fp, "id-1", NOW)).isEqualTo(1L);

        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                any(RedisScript.class),
                keys.capture(),
                eq(Long.toString(NOW.toEpochMilli())),
                eq("60000"),                                                // window millis
                eq(Integer.toString(NearDuplicateIndex.RECENT_CAP)),
                eq(member(fp, "id-1")));
        assertThat(keys.getValue()).containsExactly(NearDuplicateIndex.KEY);
    }
}
