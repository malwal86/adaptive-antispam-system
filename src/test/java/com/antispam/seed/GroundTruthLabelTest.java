package com.antispam.seed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GroundTruthLabelTest {

    @Test
    void maps_the_spamassassin_ham_variants_to_ham() {
        assertThat(GroundTruthLabel.fromDirectoryName("easy_ham")).isEqualTo(GroundTruthLabel.HAM);
        assertThat(GroundTruthLabel.fromDirectoryName("hard_ham")).isEqualTo(GroundTruthLabel.HAM);
        assertThat(GroundTruthLabel.fromDirectoryName("ham")).isEqualTo(GroundTruthLabel.HAM);
    }

    @Test
    void maps_the_spam_and_phish_class_directories() {
        assertThat(GroundTruthLabel.fromDirectoryName("spam")).isEqualTo(GroundTruthLabel.SPAM);
        assertThat(GroundTruthLabel.fromDirectoryName("phish")).isEqualTo(GroundTruthLabel.PHISH);
        assertThat(GroundTruthLabel.fromDirectoryName("phishing")).isEqualTo(GroundTruthLabel.PHISH);
    }

    @Test
    void is_case_insensitive() {
        assertThat(GroundTruthLabel.fromDirectoryName("SPAM")).isEqualTo(GroundTruthLabel.SPAM);
    }

    @Test
    void rejects_an_unrecognized_class_directory_naming_the_offender() {
        assertThatThrownBy(() -> GroundTruthLabel.fromDirectoryName("misc"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("misc");
    }

    @Test
    void db_value_round_trips_and_is_lowercase() {
        for (GroundTruthLabel label : GroundTruthLabel.values()) {
            assertThat(GroundTruthLabel.fromDbValue(label.dbValue())).isEqualTo(label);
        }
        assertThat(GroundTruthLabel.SPAM.dbValue()).isEqualTo("spam");
    }
}
