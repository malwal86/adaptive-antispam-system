package com.antispam.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The partition key derived from a sender identity. Stability is the contract
 * that matters: the same sender must always map to the same key so that every
 * message from that sender lands on one partition (per-sender serialization,
 * lock-free reputation later). See {@link SenderKey}.
 */
class SenderKeyTest {

    @Test
    void normalizes_a_sender_address_to_lowercase() {
        assertThat(SenderKey.of("Newsletter@Deals.Example.Net", "deals.example.net"))
                .isEqualTo("newsletter@deals.example.net");
    }

    @Test
    void trims_surrounding_whitespace() {
        assertThat(SenderKey.of("  sender@example.com  ", "example.com"))
                .isEqualTo("sender@example.com");
    }

    @Test
    void maps_the_same_sender_to_the_same_key_regardless_of_case_or_spacing() {
        String a = SenderKey.of("Sender@Example.com", "example.com");
        String b = SenderKey.of("  sender@example.COM ", "example.com");
        assertThat(a).isEqualTo(b);
    }

    @Test
    void falls_back_to_the_sender_domain_when_the_address_is_missing() {
        assertThat(SenderKey.of(null, "Example.com")).isEqualTo("example.com");
        assertThat(SenderKey.of("   ", "example.com")).isEqualTo("example.com");
    }

    @Test
    void uses_the_unknown_bucket_when_neither_address_nor_domain_is_present() {
        assertThat(SenderKey.of(null, null)).isEqualTo(SenderKey.UNKNOWN);
        assertThat(SenderKey.of("  ", "  ")).isEqualTo(SenderKey.UNKNOWN);
    }

    @Test
    void never_returns_null_or_blank() {
        assertThat(SenderKey.of(null, null)).isNotBlank();
        assertThat(SenderKey.of("a@b.com", null)).isNotBlank();
    }
}
