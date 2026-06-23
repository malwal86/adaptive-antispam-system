package com.antispam.feedback;

import com.antispam.decision.Probabilities;

/**
 * One persona as declared in config, bound from an entry of {@code antispam.personas.definitions}
 * (story 07.01). A persona is data, not code: adding a benign or malicious type is a new YAML
 * entry here (AC 4), and the biases are validated at this boundary so a bad config fails the
 * boot loudly rather than seeding an invalid row.
 *
 * @param name           stable, unique persona name
 * @param clickBias      click probability-weight, in {@code [0,1]}
 * @param reportBias     report probability-weight, in {@code [0,1]}
 * @param riskTolerance  risk tolerance, in {@code [0,1]}
 * @param malicious      whether the persona attacks the feedback channel (folded into config_json)
 */
public record PersonaDefinition(
        String name,
        double clickBias,
        double reportBias,
        double riskTolerance,
        boolean malicious) {

    public PersonaDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("antispam.personas.definitions[].name is required");
        }
        Probabilities.requireUnit("antispam.personas.definitions[" + name + "].click-bias", clickBias);
        Probabilities.requireUnit("antispam.personas.definitions[" + name + "].report-bias", reportBias);
        Probabilities.requireUnit("antispam.personas.definitions[" + name + "].risk-tolerance", riskTolerance);
    }

    /** The persisted persona: a content-addressed id, with the malicious flag folded into config_json. */
    public Persona toPersona() {
        return new Persona(
                Persona.idForName(name), name, clickBias, reportBias, riskTolerance,
                new PersonaConfig(malicious));
    }
}
