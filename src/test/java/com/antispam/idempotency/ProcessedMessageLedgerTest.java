package com.antispam.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract for the dedupe primitive: {@code claim} returns whether <em>this</em>
 * delivery won the right to process a key, and a lost claim (a redelivery) is
 * counted, never silent. The {@code INSERT ... ON CONFLICT} itself is exercised
 * against a real database in {@code IdempotentProcessingIntegrationTest}; here the
 * {@link JdbcTemplate} is mocked so the test pins the claim/duplicate decision and
 * the metric — the logic that sits above SQL — in isolation.
 */
@ExtendWith(MockitoExtension.class)
class ProcessedMessageLedgerTest {

    private static final String GROUP = "feature-extractor";
    private static final String KEY = "11111111-1111-1111-1111-111111111111";

    @Mock
    private JdbcTemplate jdbc;

    private SimpleMeterRegistry meters;
    private ProcessedMessageLedger ledger;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        ledger = new ProcessedMessageLedger(jdbc, meters);
    }

    @Test
    void claim_wins_on_the_first_delivery() {
        // A fresh insert affects one row.
        when(jdbc.update(any(String.class), eq(GROUP), eq(KEY))).thenReturn(1);

        assertThat(ledger.claim(GROUP, KEY)).isTrue();
    }

    @Test
    void the_first_delivery_counts_no_duplicate() {
        when(jdbc.update(any(String.class), eq(GROUP), eq(KEY))).thenReturn(1);

        ledger.claim(GROUP, KEY);

        assertThat(duplicateCount(GROUP)).isZero();
    }

    @Test
    void claim_loses_on_a_redelivery() {
        // ON CONFLICT DO NOTHING affects zero rows when the key already exists.
        when(jdbc.update(any(String.class), eq(GROUP), eq(KEY))).thenReturn(0);

        assertThat(ledger.claim(GROUP, KEY)).isFalse();
    }

    @Test
    void a_redelivery_increments_the_duplicate_counter() {
        when(jdbc.update(any(String.class), eq(GROUP), eq(KEY))).thenReturn(0);

        ledger.claim(GROUP, KEY);

        assertThat(duplicateCount(GROUP)).isEqualTo(1.0);
    }

    @Test
    void duplicate_counts_are_scoped_per_consumer_group() {
        when(jdbc.update(any(String.class), eq("feature-extractor"), eq(KEY))).thenReturn(0);
        when(jdbc.update(any(String.class), eq("reputation"), eq(KEY))).thenReturn(0);

        ledger.claim("feature-extractor", KEY);
        ledger.claim("reputation", KEY);

        assertThat(duplicateCount("feature-extractor")).isEqualTo(1.0);
        assertThat(duplicateCount("reputation")).isEqualTo(1.0);
    }

    private double duplicateCount(String group) {
        Counter counter = meters.find(ProcessedMessageLedger.DUPLICATE_COUNTER)
                .tag("consumer.group", group)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
