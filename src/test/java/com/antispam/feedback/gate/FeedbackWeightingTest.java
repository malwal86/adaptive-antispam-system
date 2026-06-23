package com.antispam.feedback.gate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.antispam.feedback.Persona;
import com.antispam.feedback.PersonaConfig;
import org.junit.jupiter.api.Test;

/**
 * The per-item weighting function (story 07.03): {@code trust × confidence}, where a good-faith
 * persona is fully trusted and a malicious one is down-weighted to {@code maliciousTrust}. This is
 * the "single high-bias report is down-weighted" half of the defence (AC 1).
 */
class FeedbackWeightingTest {

    private static final double MALICIOUS_TRUST = 0.1;

    private static Persona persona(boolean malicious) {
        return new Persona(Persona.idForName(malicious ? "bomber" : "honest"),
                malicious ? "bomber" : "honest", 0.5, 0.5, 0.5, new PersonaConfig(malicious));
    }

    @Test
    void a_good_faith_persona_is_fully_trusted() {
        assertThat(FeedbackWeighting.trust(persona(false), MALICIOUS_TRUST)).isEqualTo(1.0);
    }

    @Test
    void a_malicious_persona_is_down_weighted_to_malicious_trust() {
        assertThat(FeedbackWeighting.trust(persona(true), MALICIOUS_TRUST)).isEqualTo(MALICIOUS_TRUST);
    }

    @Test
    void weight_is_the_product_of_trust_and_confidence() {
        assertThat(FeedbackWeighting.weight(1.0, 0.8)).isCloseTo(0.8, within(1e-12));
        assertThat(FeedbackWeighting.weight(MALICIOUS_TRUST, 0.8)).isCloseTo(0.08, within(1e-12));
    }

    @Test
    void rejects_out_of_range_confidence() {
        assertThatThrownBy(() -> FeedbackWeighting.weight(1.0, 1.5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
