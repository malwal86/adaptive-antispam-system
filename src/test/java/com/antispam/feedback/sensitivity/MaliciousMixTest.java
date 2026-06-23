package com.antispam.feedback.sensitivity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * The population split per sweep point (story 07.04): a malicious fraction maps to distinct bomber
 * and good-faith counts, with the malicious half split across the two attack vectors.
 */
class MaliciousMixTest {

    @Test
    void zero_fraction_is_all_good_faith() {
        MaliciousMix mix = MaliciousMix.forFraction(10, 0.0);
        assertThat(mix.bomberCount()).isZero();
        assertThat(mix.genuine()).isEqualTo(10);
    }

    @Test
    void full_fraction_is_all_malicious() {
        MaliciousMix mix = MaliciousMix.forFraction(10, 1.0);
        assertThat(mix.bomberCount()).isEqualTo(10);
        assertThat(mix.genuine()).isZero();
    }

    @Test
    void splits_the_malicious_half_across_both_vectors_with_the_odd_one_reporting() {
        MaliciousMix mix = MaliciousMix.forFraction(10, 0.5);
        assertThat(mix.bomberCount()).isEqualTo(5);
        assertThat(mix.reportBombers()).isEqualTo(3); // ceil
        assertThat(mix.rescueBombers()).isEqualTo(2); // floor
        assertThat(mix.genuine()).isEqualTo(5);
    }

    @Test
    void rounds_the_malicious_count_to_the_nearest_persona() {
        // 0.25 of 10 = 2.5 → 3 malicious (2 report, 1 rescue), 7 genuine.
        MaliciousMix mix = MaliciousMix.forFraction(10, 0.25);
        assertThat(mix.bomberCount()).isEqualTo(3);
        assertThat(mix.reportBombers()).isEqualTo(2);
        assertThat(mix.rescueBombers()).isEqualTo(1);
        assertThat(mix.genuine()).isEqualTo(7);
    }

    @Test
    void the_parts_always_sum_to_the_population() {
        for (double f = 0.0; f <= 1.0; f += 0.1) {
            assertThat(MaliciousMix.forFraction(13, f).populationSize()).isEqualTo(13);
        }
    }

    @Test
    void rejects_an_out_of_range_fraction() {
        assertThatThrownBy(() -> MaliciousMix.forFraction(10, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
