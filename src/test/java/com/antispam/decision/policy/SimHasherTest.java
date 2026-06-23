package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The SimHash fingerprinter's locality-sensitive property (story 06.02): trivially-varied texts land
 * a small Hamming distance apart (so a templated blast is recognized), unrelated texts land far apart
 * (so distinct ham is not), and the fingerprint is deterministic and zero for token-free input.
 */
class SimHasherTest {

    private final SimHasher hasher = new SimHasher();

    // A paragraph-length template, the realistic case for SimHash (short texts are noisier).
    private static final String TEMPLATE = """
            Dear customer, your account has been temporarily suspended due to unusual activity.
            Please verify your identity within 24 hours by clicking the secure link below to avoid
            permanent closure. Our support team is available around the clock to assist you with any
            questions about restoring full access to your account and protecting your information.
            """;

    @Test
    void a_trivially_varied_message_is_a_small_hamming_distance_from_the_original() {
        // Swap the salutation token and add whitespace — the kind of trivial variation a template blast
        // uses to dodge exact-hash dedup.
        String varied = TEMPLATE.replace("Dear customer", "Dear   valued user");

        int distance = SimHasher.hammingDistance(hasher.fingerprint(TEMPLATE), hasher.fingerprint(varied));

        // Well within the detector's default near-dup radius (6) and far from the unrelated case (>=12).
        assertThat(distance).isLessThanOrEqualTo(6);
    }

    @Test
    void an_unrelated_message_is_a_large_hamming_distance_from_the_template() {
        String unrelated = """
                Hi team, attached are the notes from this morning's planning sync. We agreed to ship the
                analytics dashboard next sprint, move the billing migration to Q3, and revisit the
                onboarding flow once the new designs are ready. Let me know if I missed anything.
                """;

        int distance =
                SimHasher.hammingDistance(hasher.fingerprint(TEMPLATE), hasher.fingerprint(unrelated));

        assertThat(distance).isGreaterThanOrEqualTo(12);
    }

    @Test
    void is_deterministic_for_the_same_text() {
        assertThat(hasher.fingerprint(TEMPLATE)).isEqualTo(hasher.fingerprint(TEMPLATE));
    }

    @Test
    void normalizes_whitespace_and_case_so_cosmetic_changes_do_not_move_the_fingerprint() {
        String cosmetic = TEMPLATE.toUpperCase().replaceAll("\\s+", "  ");

        assertThat(hasher.fingerprint(cosmetic)).isEqualTo(hasher.fingerprint(TEMPLATE));
    }

    @Test
    void yields_zero_for_token_free_text() {
        assertThat(hasher.fingerprint(null)).isZero();
        assertThat(hasher.fingerprint("")).isZero();
        assertThat(hasher.fingerprint("   \n\t  ")).isZero();
        assertThat(hasher.fingerprint("...---...")).isZero();
    }
}
