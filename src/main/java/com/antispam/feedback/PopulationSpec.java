package com.antispam.feedback;

import java.util.Map;

/**
 * A reproducible request for a synthetic-user population (story 07.01): which personas in what
 * proportion, how many users, under which seed. The same spec always yields the same population
 * ({@link PersonaPopulationAssembler}, AC 5), so two simulation runs can be compared honestly.
 *
 * @param seed    seeds the deterministic shuffle of the assembled members
 * @param size    total number of users to assemble; at least 1
 * @param weights relative weight per persona name (e.g. {@code {trusting: 7, bomber: 1}}); the
 *                mix is parameterizable (AC 2). Non-empty, every weight strictly positive. The
 *                names must exist in the seeded catalogue — that is checked at assembly time,
 *                where the catalogue is known.
 */
public record PopulationSpec(long seed, int size, Map<String, Integer> weights) {

    public PopulationSpec {
        if (size < 1) {
            throw new IllegalArgumentException("population size must be at least 1 but was " + size);
        }
        if (weights == null || weights.isEmpty()) {
            throw new IllegalArgumentException("population weights must name at least one persona");
        }
        weights = Map.copyOf(weights);
        for (Map.Entry<String, Integer> weight : weights.entrySet()) {
            if (weight.getValue() == null || weight.getValue() < 1) {
                throw new IllegalArgumentException(
                        "weight for persona '" + weight.getKey() + "' must be a positive integer");
            }
        }
    }
}
