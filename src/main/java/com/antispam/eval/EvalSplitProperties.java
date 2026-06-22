package com.antispam.eval;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The bootstrap split's two knobs, bound from {@code antispam.eval-split} (stories
 * 11.01 / 11.03). They live in config, not code, so the held-out fraction and the
 * tie-break seed can be tuned without a redeploy, and the seed is recorded
 * explicitly to make a split reproducible from configuration alone.
 *
 * <p>The default {@code eval-fraction} of {@code 0.2} holds out a fifth of the
 * corpus — enough to calibrate and judge against, while leaving the bulk for
 * training.
 *
 * @param evalFraction target share of emails to hold out for eval, in {@code (0, 1)}
 * @param seed         deterministic tie-breaker among families of identical recency
 */
@Validated
@ConfigurationProperties(prefix = "antispam.eval-split")
public record EvalSplitProperties(double evalFraction, long seed) {

    /** Surfaces the same {@code (0, 1)} contract {@link SplitConfig} enforces, at startup. */
    public EvalSplitProperties {
        if (!(evalFraction > 0.0 && evalFraction < 1.0)) {
            throw new IllegalArgumentException(
                    "antispam.eval-split.eval-fraction must be in (0, 1), was: " + evalFraction);
        }
    }

    /** The splitter configuration these properties describe. */
    public SplitConfig toSplitConfig() {
        return new SplitConfig(evalFraction, seed);
    }
}
