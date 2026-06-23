package com.antispam.feedback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Assembles a reproducible synthetic-user population from a {@link PopulationSpec} and the seeded
 * persona catalogue (story 07.01).
 *
 * <p>Counts per persona come from the <b>largest-remainder method</b>: each persona gets the floor
 * of its exact share, then the few leftover slots go to the largest fractional remainders. This
 * makes the population total <em>exactly</em> the requested size (AC 2) — naive rounding would
 * over- or under-shoot — and resolves ties by name so the allocation is deterministic. The members
 * are then shuffled with a {@link Random} seeded from the spec, so two runs of the same spec
 * produce the identical population (AC 5) while a realistic run isn't blocked into per-persona runs.
 */
@Component
public class PersonaPopulationAssembler {

    private final PersonaRepository repository;

    @Autowired
    public PersonaPopulationAssembler(PersonaRepository repository) {
        this.repository = repository;
    }

    /** Assembles from the personas currently seeded in {@code user_personas}. */
    public Population assemble(PopulationSpec spec) {
        return assemble(spec, repository.findAll());
    }

    /**
     * Assembles from an explicit catalogue — the pure form the unit tests pin down, and what the
     * repository-backed overload delegates to.
     *
     * @throws IllegalArgumentException if the spec weights a persona not present in {@code catalogue}
     */
    public Population assemble(PopulationSpec spec, List<Persona> catalogue) {
        Map<String, Persona> byName = catalogue.stream()
                .collect(Collectors.toMap(Persona::name, Function.identity()));
        for (String name : spec.weights().keySet()) {
            if (!byName.containsKey(name)) {
                throw new IllegalArgumentException(
                        "population spec references unknown persona: " + name);
            }
        }

        Map<String, Integer> counts = allocate(spec.size(), spec.weights());
        List<Persona> members = new ArrayList<>(spec.size());
        // Build in sorted-name order first (stable, catalogue-independent), then shuffle by seed.
        counts.forEach((name, count) -> {
            Persona persona = byName.get(name);
            for (int i = 0; i < count; i++) {
                members.add(persona);
            }
        });
        Collections.shuffle(members, new Random(spec.seed()));
        return new Population(members);
    }

    /**
     * Largest-remainder allocation of {@code size} slots across {@code weights}, names visited in
     * sorted order so the remainder tiebreak is deterministic. Returns counts in sorted-name order.
     */
    private static Map<String, Integer> allocate(int size, Map<String, Integer> weights) {
        List<String> names = weights.keySet().stream().sorted().toList();
        long totalWeight = weights.values().stream().mapToLong(Integer::longValue).sum();

        Map<String, Integer> counts = new LinkedHashMap<>();
        List<Remainder> remainders = new ArrayList<>(names.size());
        int assigned = 0;
        for (String name : names) {
            double exact = (double) size * weights.get(name) / totalWeight;
            int floor = (int) Math.floor(exact);
            counts.put(name, floor);
            assigned += floor;
            remainders.add(new Remainder(name, exact - floor));
        }

        // Each floor drops < 1 slot, so leftover < names.size(): the loop below stays in range.
        int leftover = size - assigned;
        remainders.sort(Comparator.comparingDouble(Remainder::fraction).reversed()
                .thenComparing(Remainder::name));
        for (int i = 0; i < leftover; i++) {
            counts.merge(remainders.get(i).name(), 1, Integer::sum);
        }
        return counts;
    }

    private record Remainder(String name, double fraction) {
    }
}
