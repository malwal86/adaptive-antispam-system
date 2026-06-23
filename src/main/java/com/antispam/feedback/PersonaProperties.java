package com.antispam.feedback;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The synthetic-user population config, bound from {@code antispam.personas} (story 07.01).
 *
 * <p>{@code seedOnStartup} mirrors {@code antispam.seed.enabled}: off by default so a normal
 * boot — and the Postgres-only tests — never touch {@code user_personas}; the simulator deploy
 * (or a seeding test) turns it on. {@code definitions} is the config-only population catalogue
 * (AC 4); duplicate names are rejected here because they would otherwise collide on the
 * content-addressed persona id at seed time.
 *
 * @param seedOnStartup whether {@link PersonaSeeder} seeds the definitions into the table at boot
 * @param definitions   the persona catalogue, defined entirely in config; never null (defaults empty)
 */
@Validated
@ConfigurationProperties(prefix = "antispam.personas")
public record PersonaProperties(boolean seedOnStartup, List<PersonaDefinition> definitions) {

    public PersonaProperties {
        definitions = definitions == null ? List.of() : List.copyOf(definitions);
        long distinctNames = definitions.stream().map(PersonaDefinition::name).distinct().count();
        if (distinctNames != definitions.size()) {
            throw new IllegalArgumentException(
                    "antispam.personas.definitions must not contain duplicate names");
        }
    }
}
