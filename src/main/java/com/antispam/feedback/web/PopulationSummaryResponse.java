package com.antispam.feedback.web;

import com.antispam.feedback.Persona;
import com.antispam.feedback.Population;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The assembled population summarized for the API (story 07.01): the seed and total size, plus the
 * realized count per persona name. Returning counts rather than the full member list keeps the
 * response small while still proving the mix was honored — the same spec always yields the same
 * counts.
 *
 * @param seed   the spec seed that produced this population
 * @param size   total number of users assembled
 * @param counts realized users per persona name (sorted by name for a stable response)
 */
public record PopulationSummaryResponse(long seed, int size, Map<String, Long> counts) {

    public static PopulationSummaryResponse from(long seed, Population population) {
        Map<String, Long> counts = population.members().stream()
                .collect(Collectors.groupingBy(Persona::name, TreeMap::new, Collectors.counting()));
        return new PopulationSummaryResponse(seed, population.size(), counts);
    }
}
