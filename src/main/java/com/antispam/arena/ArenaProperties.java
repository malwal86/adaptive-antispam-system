package com.antispam.arena;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the adversarial arena (story 08.01). The {@code attackerModel} is the PRD's
 * configurable {@code attacker_model} (§Subsystem 6): the red-team's model is a config value, not a
 * code constant, so it can be swapped per run without an edit. {@code enabled} gates the live
 * attacker the same way {@code antispam.llm.enabled} gates the defender's fallback, so local dev and
 * the full-context tests stay provider-free unless explicitly turned on.
 *
 * @param enabled       whether the live attacker port may call a provider; default off
 * @param attackerModel the model name the attacker port requests; recorded on every variant it mints
 */
@ConfigurationProperties(prefix = "antispam.arena")
public record ArenaProperties(boolean enabled, String attackerModel) {

    public ArenaProperties {
        if (attackerModel == null || attackerModel.isBlank()) {
            attackerModel = "gpt-4o-mini";
        }
    }
}
