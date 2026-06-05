package com.antispam.seed;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Controls one-shot corpus seeding at startup, bound from {@code antispam.seed}.
 * Disabled by default so a normal boot never touches the corpus; the seed script
 * (or an operator) sets {@code antispam.seed.enabled=true} together with a
 * {@code corpus-dir} to run a load.
 *
 * @param enabled   whether to run the seed loader on startup
 * @param corpusDir filesystem path to the corpus root ({@code <dataset>/<class>/<files>}),
 *                  required when {@code enabled} is true
 */
@ConfigurationProperties(prefix = "antispam.seed")
public record SeedProperties(boolean enabled, String corpusDir) {
}
