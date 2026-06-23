package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The persona domain invariants (story 07.01): biases are probabilities in [0,1], the
 * malicious flag is read from the JSONB config, and identity is content-addressed from
 * the name so re-seeding the same definition resolves to the same row (AC 5).
 */
class PersonaTest {

    private static Persona persona(double click, double report, double risk, boolean malicious) {
        return new Persona(UUID.randomUUID(), "p", click, report, risk, new PersonaConfig(malicious));
    }

    @Test
    void exposes_malicious_flag_from_config() {
        assertThat(persona(0.5, 0.5, 0.5, true).malicious()).isTrue();
        assertThat(persona(0.5, 0.5, 0.5, false).malicious()).isFalse();
    }

    @Test
    void accepts_biases_at_the_unit_bounds() {
        assertThat(persona(0.0, 0.0, 0.0, false).clickBias()).isZero();
        assertThat(persona(1.0, 1.0, 1.0, false).riskTolerance()).isEqualTo(1.0);
    }

    @Test
    void rejects_click_bias_above_one() {
        assertThatThrownBy(() -> persona(1.0001, 0.5, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("clickBias");
    }

    @Test
    void rejects_negative_report_bias() {
        assertThatThrownBy(() -> persona(0.5, -0.01, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reportBias");
    }

    @Test
    void rejects_risk_tolerance_above_one() {
        assertThatThrownBy(() -> persona(0.5, 0.5, 1.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("riskTolerance");
    }

    @Test
    void rejects_blank_name() {
        assertThatThrownBy(() -> new Persona(UUID.randomUUID(), "  ", 0.5, 0.5, 0.5, new PersonaConfig(false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void rejects_null_config() {
        assertThatThrownBy(() -> new Persona(UUID.randomUUID(), "p", 0.5, 0.5, 0.5, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("config");
    }

    @Test
    void id_is_stable_for_a_name_and_distinct_across_names() {
        assertThat(Persona.idForName("trusting-clicker")).isEqualTo(Persona.idForName("trusting-clicker"));
        assertThat(Persona.idForName("trusting-clicker")).isNotEqualTo(Persona.idForName("report-bomber"));
    }
}
