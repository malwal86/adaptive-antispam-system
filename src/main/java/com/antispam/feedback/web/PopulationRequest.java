package com.antispam.feedback.web;

import com.antispam.feedback.PopulationSpec;
import java.util.Map;

/**
 * Request body for assembling a population (story 07.01): the seed, the size, and the relative
 * weight per persona name. Maps straight onto {@link PopulationSpec}, whose constructor validates
 * the values.
 *
 * @param seed    seeds the deterministic shuffle (same seed + spec → same population)
 * @param size    number of synthetic users to assemble
 * @param weights relative weight per persona name (the parameterizable mix)
 */
public record PopulationRequest(long seed, int size, Map<String, Integer> weights) {

    public PopulationSpec toSpec() {
        return new PopulationSpec(seed, size, weights);
    }
}
