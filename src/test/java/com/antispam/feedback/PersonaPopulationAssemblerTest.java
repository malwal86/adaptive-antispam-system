package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Population assembly (story 07.01) over an in-memory persona catalogue — no database, so the
 * allocation and determinism are pinned directly. Covers: the mix is honored exactly (AC 2),
 * the assembled population reproduces byte-for-byte from the same spec (AC 5), the total is
 * exactly the requested size even when the weights don't divide it, and unknown / invalid specs
 * are rejected loudly.
 */
class PersonaPopulationAssemblerTest {

    private static Persona persona(String name) {
        return new Persona(Persona.idForName(name), name, 0.5, 0.5, 0.5, new PersonaConfig(false));
    }

    private static final List<Persona> CATALOGUE =
            List.of(persona("a"), persona("b"), persona("c"));

    private final PersonaPopulationAssembler assembler = new PersonaPopulationAssembler(null);

    private static Map<String, Long> countsByName(Population population) {
        return population.members().stream()
                .collect(Collectors.groupingBy(Persona::name, Collectors.counting()));
    }

    @Test
    void honors_the_requested_proportions_exactly() {
        PopulationSpec spec = new PopulationSpec(1L, 100, Map.of("a", 7, "b", 2, "c", 1));

        Population population = assembler.assemble(spec, CATALOGUE);

        assertThat(population.size()).isEqualTo(100);
        assertThat(countsByName(population)).containsExactlyInAnyOrderEntriesOf(
                Map.of("a", 70L, "b", 20L, "c", 10L));
    }

    @Test
    void totals_exactly_the_requested_size_when_weights_do_not_divide_it() {
        PopulationSpec spec = new PopulationSpec(1L, 10, Map.of("a", 1, "b", 1, "c", 1));

        Population population = assembler.assemble(spec, CATALOGUE);

        assertThat(population.size()).isEqualTo(10);
        // 10 split three ways by largest remainder = 4/3/3, leftover to the first by name order.
        assertThat(countsByName(population)).containsExactlyInAnyOrderEntriesOf(
                Map.of("a", 4L, "b", 3L, "c", 3L));
    }

    @Test
    void same_spec_reproduces_an_identical_population() {
        PopulationSpec spec = new PopulationSpec(42L, 50, Map.of("a", 3, "b", 2));

        List<UUID> first = assembler.assemble(spec, CATALOGUE).members().stream()
                .map(Persona::id).toList();
        List<UUID> second = assembler.assemble(spec, CATALOGUE).members().stream()
                .map(Persona::id).toList();

        assertThat(second).containsExactlyElementsOf(first);
    }

    @Test
    void different_seeds_keep_the_mix_but_change_the_ordering() {
        PopulationSpec specA = new PopulationSpec(1L, 60, Map.of("a", 1, "b", 1, "c", 1));
        PopulationSpec specB = new PopulationSpec(2L, 60, Map.of("a", 1, "b", 1, "c", 1));

        Population a = assembler.assemble(specA, CATALOGUE);
        Population b = assembler.assemble(specB, CATALOGUE);

        assertThat(countsByName(a)).isEqualTo(countsByName(b));
        assertThat(a.members().stream().map(Persona::name).toList())
                .isNotEqualTo(b.members().stream().map(Persona::name).toList());
    }

    @Test
    void rejects_a_weight_for_an_unknown_persona() {
        PopulationSpec spec = new PopulationSpec(1L, 10, Map.of("a", 1, "ghost", 1));

        assertThatThrownBy(() -> assembler.assemble(spec, CATALOGUE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");
    }

    @Test
    void assembles_only_the_personas_a_subset_spec_references() {
        PopulationSpec spec = new PopulationSpec(7L, 20, Map.of("b", 1));

        Population population = assembler.assemble(spec, CATALOGUE);

        assertThat(countsByName(population)).containsExactlyInAnyOrderEntriesOf(Map.of("b", 20L));
    }

    @Test
    void spec_rejects_non_positive_size_empty_or_non_positive_weights() {
        assertThatThrownBy(() -> new PopulationSpec(1L, 0, Map.of("a", 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PopulationSpec(1L, 10, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PopulationSpec(1L, 10, Map.of("a", 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
