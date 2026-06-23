package com.antispam.feedback.web;

import com.antispam.feedback.PopulationSpec;
import java.util.Map;

/**
 * Request body for a feedback-simulation run (story 07.02): the population spec (seed, size,
 * persona weights) plus how many decided emails to stream through it.
 *
 * @param seed    seeds the run (same seed + spec + corpus → same actions)
 * @param size    number of synthetic users in the population
 * @param weights relative weight per persona name (the parameterizable mix)
 * @param limit   maximum number of decided-and-labeled emails to run through the population
 */
public record FeedbackSimulationRequest(long seed, int size, Map<String, Integer> weights, int limit) {

    public PopulationSpec toSpec() {
        return new PopulationSpec(seed, size, weights);
    }
}
