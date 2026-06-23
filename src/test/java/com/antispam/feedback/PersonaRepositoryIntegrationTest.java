package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Persona persistence against real Postgres (story 07.01): seeding writes rows with the biases and
 * the malicious flag round-tripped through {@code config_json} JSONB (AC 1), re-seeding is
 * idempotent (no duplicate row), and the assembler resolves a population from the seeded catalogue.
 *
 * <p>The suite shares one database, so tests use uniquely-named personas and scope every assertion
 * to those names rather than to global counts.
 */
class PersonaRepositoryIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PersonaRepository repository;

    @Autowired
    private PersonaPopulationAssembler assembler;

    private static Persona persona(String name, boolean malicious) {
        return new PersonaDefinition(name, 0.7, 0.3, 0.6, malicious).toPersona();
    }

    @Test
    void seeds_rows_with_biases_and_the_malicious_flag_from_config_json() {
        String benign = "benign-" + UUID.randomUUID();
        String evil = "evil-" + UUID.randomUUID();

        repository.seed(List.of(persona(benign, false), persona(evil, true)));

        Persona reloaded = repository.findByName(evil).orElseThrow();
        assertThat(reloaded.clickBias()).isEqualTo(0.7);
        assertThat(reloaded.reportBias()).isEqualTo(0.3);
        assertThat(reloaded.riskTolerance()).isEqualTo(0.6);
        assertThat(reloaded.malicious()).isTrue();
        assertThat(reloaded.id()).isEqualTo(Persona.idForName(evil));
        assertThat(repository.findByName(benign).orElseThrow().malicious()).isFalse();
    }

    @Test
    void re_seeding_the_same_persona_does_not_duplicate_it() {
        String name = "idem-" + UUID.randomUUID();

        repository.seed(List.of(persona(name, false)));
        repository.seed(List.of(persona(name, true))); // same id (name-derived), updated flag

        long matching = repository.findAll().stream().filter(p -> p.name().equals(name)).count();
        assertThat(matching).isEqualTo(1);
        assertThat(repository.findByName(name).orElseThrow().malicious()).isTrue();
    }

    @Test
    void assembles_a_population_from_the_seeded_catalogue() {
        String a = "pa-" + UUID.randomUUID();
        String b = "pb-" + UUID.randomUUID();
        repository.seed(List.of(persona(a, false), persona(b, true)));

        Population population = assembler.assemble(
                new PopulationSpec(9L, 40, Map.of(a, 3, b, 1)));

        assertThat(population.size()).isEqualTo(40);
        long aCount = population.members().stream().filter(p -> p.name().equals(a)).count();
        long bCount = population.members().stream().filter(p -> p.name().equals(b)).count();
        assertThat(aCount).isEqualTo(30);
        assertThat(bCount).isEqualTo(10);
    }
}
