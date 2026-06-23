package com.antispam.feedback;

import com.antispam.decision.Probabilities;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * A synthetic user with behavioral biases (story 07.01) — one row of {@code user_personas}.
 * The biases are the knobs the truth-conditioned action sampler (07.02) reads and the
 * sensitivity sweep (07.04) varies: {@code clickBias} and {@code reportBias} are how readily
 * the persona clicks a link or reports mail, {@code riskTolerance} how willing it is to engage
 * with risky content. All three are probabilities in {@code [0,1]}.
 *
 * <p>Identity is content-addressed from the {@code name} ({@link #idForName}): re-seeding the
 * same definition writes the same row rather than a duplicate, and a population spec naming a
 * persona resolves to the same persona across runs and machines — the reproducibility AC 5
 * leans on.
 *
 * @param id             content-addressed id (UUIDv3 over the name)
 * @param name           stable, unique persona name (the key the population spec references)
 * @param clickBias      probability-weight that the persona clicks, in {@code [0,1]}
 * @param reportBias     probability-weight that the persona reports, in {@code [0,1]}
 * @param riskTolerance  willingness to engage with risky content, in {@code [0,1]}
 * @param config         the JSONB-backed config, carrying the malicious flag
 */
public record Persona(
        UUID id,
        String name,
        double clickBias,
        double reportBias,
        double riskTolerance,
        PersonaConfig config) {

    public Persona {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("persona name is required");
        }
        Probabilities.requireUnit("clickBias", clickBias);
        Probabilities.requireUnit("reportBias", reportBias);
        Probabilities.requireUnit("riskTolerance", riskTolerance);
        if (config == null) {
            throw new IllegalArgumentException("persona config is required");
        }
    }

    /** Whether this persona attacks the feedback channel rather than acting in good faith. */
    public boolean malicious() {
        return config.malicious();
    }

    /**
     * The content-addressed id for a persona name: UUIDv3 over {@code "persona:" + name}.
     * Identity comes from the name, so re-seeding a definition writes the same row (no
     * duplicates) and the same population spec resolves to the same personas across runs and
     * machines (AC 5). The {@code "persona:"} prefix namespaces it away from other UUIDv3 ids
     * derived from bare strings elsewhere.
     */
    public static UUID idForName(String name) {
        return UUID.nameUUIDFromBytes(("persona:" + name).getBytes(StandardCharsets.UTF_8));
    }
}
