package com.antispam.feedback;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The startup seeding gate (story 07.01): seeding runs only when {@code seed-on-startup=true} and
 * there is something to seed, so a normal boot — and the Postgres-only tests — never touch the table.
 */
@ExtendWith(MockitoExtension.class)
class PersonaSeederTest {

    @Mock
    private PersonaRepository repository;

    @Captor
    private ArgumentCaptor<List<Persona>> seeded;

    private PersonaSeeder seeder(PersonaProperties properties) {
        return new PersonaSeeder(properties, repository);
    }

    @Test
    void does_not_touch_the_repository_when_seeding_is_off() {
        var props = new PersonaProperties(false,
                List.of(new PersonaDefinition("p", 0.5, 0.5, 0.5, false)));

        seeder(props).run(null);

        verifyNoInteractions(repository);
    }

    @Test
    void does_not_seed_when_enabled_but_no_definitions() {
        seeder(new PersonaProperties(true, List.of())).run(null);

        verify(repository, never()).seed(org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void seeds_the_configured_definitions_as_personas_when_enabled() {
        var props = new PersonaProperties(true, List.of(
                new PersonaDefinition("trusting-clicker", 0.8, 0.1, 0.9, false),
                new PersonaDefinition("report-bomber", 0.1, 0.99, 0.2, true)));

        seeder(props).run(null);

        verify(repository).seed(seeded.capture());
        var personas = seeded.getValue();
        org.assertj.core.api.Assertions.assertThat(personas).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(personas)
                .extracting(Persona::name).containsExactly("trusting-clicker", "report-bomber");
        org.assertj.core.api.Assertions.assertThat(personas)
                .filteredOn(Persona::malicious).extracting(Persona::name).containsExactly("report-bomber");
    }
}
