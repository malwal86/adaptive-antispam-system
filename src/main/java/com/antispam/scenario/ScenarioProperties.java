package com.antispam.scenario;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The thunderclap scenario runner's knobs (story 12.05), bound from {@code antispam.scenario}.
 *
 * @param stepDelay   the pause between successive injected emails, so the demo's beats unfold visibly
 *                    on the live stream rather than arriving in one burst. Must be non-negative; tests
 *                    set it to zero to run the scenario as fast as possible
 * @param defaultSeed the seed used when a start request does not supply one — fixing it makes the
 *                    no-argument demo exactly reproducible
 */
@Validated
@ConfigurationProperties(prefix = "antispam.scenario")
public record ScenarioProperties(Duration stepDelay, long defaultSeed) {

    public ScenarioProperties {
        if (stepDelay == null || stepDelay.isNegative()) {
            throw new IllegalArgumentException(
                    "antispam.scenario.step-delay must be a non-negative duration but was " + stepDelay);
        }
    }
}
