package com.antispam.feedback;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Seeds the configured persona catalogue into {@code user_personas} once at startup when
 * {@code antispam.personas.seed-on-startup=true} (story 07.01). Mirrors {@link com.antispam.seed.SeedRunner}:
 * on a normal boot this is a no-op, so local dev and the Postgres-only tests never touch the table.
 * The upsert is idempotent, so re-running the simulator deploy converges to the configured set.
 */
@Component
public class PersonaSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PersonaSeeder.class);

    private final PersonaProperties properties;
    private final PersonaRepository repository;

    @Autowired
    public PersonaSeeder(PersonaProperties properties, PersonaRepository repository) {
        this.properties = properties;
        this.repository = repository;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.seedOnStartup()) {
            return;
        }
        List<Persona> personas = properties.definitions().stream()
                .map(PersonaDefinition::toPersona)
                .toList();
        if (personas.isEmpty()) {
            log.warn("antispam.personas.seed-on-startup=true but no definitions configured; nothing seeded");
            return;
        }
        repository.seed(personas);
        log.info("seeded {} personas: {}", personas.size(),
                personas.stream().map(Persona::name).toList());
    }
}
