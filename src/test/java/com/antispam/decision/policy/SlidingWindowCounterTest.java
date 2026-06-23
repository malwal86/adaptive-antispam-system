package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
 * The sliding-window counter's Java↔Lua boundary (story 06.01): it passes the key, the event time
 * and window as milliseconds, and the unique member to the script in the order the script reads
 * them, and maps the returned cardinality (or a null reply) to a count. The script's actual
 * age-out/record/count behaviour against real Redis is pinned in {@link BurstOverrideIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class SlidingWindowCounterTest {

    @Mock
    private StringRedisTemplate redis;

    @Test
    @SuppressWarnings("unchecked")
    void records_the_event_as_millis_and_returns_the_window_count() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(7L);
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);

        long count = new SlidingWindowCounter(redis)
                .recordAndCount("burst:v1:sender:s", "member-1", now, Duration.ofSeconds(60));

        assertThat(count).isEqualTo(7L);
        ArgumentCaptor<List<String>> keys = ArgumentCaptor.forClass(List.class);
        verify(redis).execute(
                any(RedisScript.class),
                keys.capture(),
                org.mockito.ArgumentMatchers.eq("1700000000000"), // now millis
                org.mockito.ArgumentMatchers.eq("60000"),          // window millis
                org.mockito.ArgumentMatchers.eq("member-1"));      // unique member
        assertThat(keys.getValue()).containsExactly("burst:v1:sender:s");
    }

    @Test
    @SuppressWarnings("unchecked")
    void treats_a_null_reply_as_a_zero_count() {
        when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any())).thenReturn(null);

        long count = new SlidingWindowCounter(redis)
                .recordAndCount("k", "m", Instant.ofEpochMilli(0L), Duration.ofSeconds(1));

        assertThat(count).isZero();
    }
}
