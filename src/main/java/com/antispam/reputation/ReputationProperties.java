package com.antispam.reputation;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Beta-reputation prior, bound from {@code antispam.reputation} (story 03.01).
 * {@code alpha} and {@code beta} are pseudo-counts of prior good/bad observations:
 * they set where a brand-new sender starts and how quickly real evidence outweighs
 * the prior. The default {@code 1/1} (uniform) starts a new sender at mean 0.5 with
 * maximum variance — the wide uncertainty that routes unseen senders to the LLM
 * (Epic 05).
 *
 * <p>Both are {@link Positive}: a proper Beta requires {@code alpha, beta > 0}, so a
 * zero/negative prior fails startup loudly rather than yielding NaN scores at
 * runtime. Kept in config (not code) so the prior is tunable without a redeploy.
 *
 * @param alpha prior pseudo-count of good observations
 * @param beta  prior pseudo-count of bad observations
 */
@Validated
@ConfigurationProperties(prefix = "antispam.reputation")
public record ReputationProperties(
        @Positive double alpha,
        @Positive double beta) {
}
