package com.antispam.feedback.web;

import com.antispam.feedback.Persona;
import java.util.UUID;

/**
 * A seeded persona as the API returns it (story 07.01): the biases plus the malicious flag lifted
 * out of {@code config_json} for a flat, readable shape.
 */
public record PersonaResponse(
        UUID id,
        String name,
        double clickBias,
        double reportBias,
        double riskTolerance,
        boolean malicious) {

    public static PersonaResponse from(Persona persona) {
        return new PersonaResponse(
                persona.id(),
                persona.name(),
                persona.clickBias(),
                persona.reportBias(),
                persona.riskTolerance(),
                persona.malicious());
    }
}
