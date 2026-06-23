package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Config-bound persona definitions (story 07.01): a benign and a malicious persona are both
 * expressible from config alone (AC 4), biases are validated at the config boundary (AC 3),
 * the malicious flag lands in {@code config_json} (AC 1), and duplicate names are rejected
 * before they could collide on the content-addressed id.
 */
class PersonaDefinitionTest {

    @Test
    void converts_a_benign_definition_to_a_persona_with_config_malicious_false() {
        PersonaDefinition def = new PersonaDefinition("trusting-clicker", 0.8, 0.1, 0.9, false);

        Persona persona = def.toPersona();

        assertThat(persona.name()).isEqualTo("trusting-clicker");
        assertThat(persona.clickBias()).isEqualTo(0.8);
        assertThat(persona.malicious()).isFalse();
        assertThat(persona.id()).isEqualTo(Persona.idForName("trusting-clicker"));
    }

    @Test
    void converts_a_malicious_definition_with_no_code_change() {
        PersonaDefinition def = new PersonaDefinition("report-bomber", 0.1, 0.99, 0.2, true);

        assertThat(def.toPersona().malicious()).isTrue();
    }

    @Test
    void rejects_out_of_range_bias_at_the_config_boundary() {
        assertThatThrownBy(() -> new PersonaDefinition("bad", 1.5, 0.1, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("click-bias");
    }

    @Test
    void rejects_blank_name() {
        assertThatThrownBy(() -> new PersonaDefinition(" ", 0.5, 0.5, 0.5, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name");
    }

    @Test
    void properties_reject_duplicate_persona_names() {
        List<PersonaDefinition> dupes = List.of(
                new PersonaDefinition("p", 0.5, 0.5, 0.5, false),
                new PersonaDefinition("p", 0.4, 0.4, 0.4, true));

        assertThatThrownBy(() -> new PersonaProperties(true, dupes))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void properties_default_a_null_definition_list_to_empty() {
        assertThat(new PersonaProperties(false, null).definitions()).isEmpty();
    }
}
