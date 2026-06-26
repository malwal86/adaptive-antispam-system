package com.antispam.privacy;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The keyed-HMAC sender pseudonym used to de-identify exports (story 14.04): it must
 * be stable for the same sender (so grouped/time-forward splits still work), differ
 * across senders, and depend on the secret key (so the real identity can't be derived
 * from the pseudonym without it).
 */
class SenderPseudonymizerTest {

    private static final byte[] KEY_A = "the-export-hmac-key-0123456789ab".getBytes(StandardCharsets.UTF_8);
    private static final byte[] KEY_B = "a-different-export-hmac-key-1234".getBytes(StandardCharsets.UTF_8);

    private final SenderPseudonymizer pseudonymizer = new SenderPseudonymizer(KEY_A);

    @Test
    void the_same_sender_maps_to_the_same_pseudonym() {
        assertThat(pseudonymizer.pseudonym("alice@example.com"))
                .isEqualTo(pseudonymizer.pseudonym("alice@example.com"));
    }

    @Test
    void different_senders_map_to_different_pseudonyms() {
        assertThat(pseudonymizer.pseudonym("alice@example.com"))
                .isNotEqualTo(pseudonymizer.pseudonym("bob@example.com"));
    }

    @Test
    void the_pseudonym_does_not_reveal_the_real_sender() {
        String pseudonym = pseudonymizer.pseudonym("alice@example.com");
        assertThat(pseudonym).doesNotContain("alice").doesNotContain("example.com");
    }

    @Test
    void a_different_key_produces_a_different_pseudonym_for_the_same_sender() {
        assertThat(pseudonymizer.pseudonym("alice@example.com"))
                .isNotEqualTo(new SenderPseudonymizer(KEY_B).pseudonym("alice@example.com"));
    }

    @Test
    void null_or_blank_senders_share_one_stable_unknown_pseudonym() {
        String unknown = pseudonymizer.pseudonym(null);
        assertThat(unknown).isEqualTo(pseudonymizer.pseudonym("")).isEqualTo(pseudonymizer.pseudonym("   "));
        assertThat(unknown).isNotBlank();
    }
}
