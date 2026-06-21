package com.antispam.reputation;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The Beta-reputation parameters, bound from {@code antispam.reputation} (stories
 * 03.01, 03.02). {@code alpha} and {@code beta} are pseudo-counts of prior good/bad
 * observations: they set where a brand-new sender starts and how quickly real
 * evidence outweighs the prior. The default {@code 1/1} (uniform) starts a new sender
 * at mean 0.5 with maximum variance — the wide uncertainty that routes unseen senders
 * to the LLM (Epic 05).
 *
 * <p>{@code halfLife} is the exponential decay half-life applied lazily at read time
 * (story 03.02): evidence older than one half-life counts for less than half, so a
 * long-dormant sender's accrued trust fades and a sudden attack isn't fully shielded
 * by old goodwill. The default {@code 7d} matches PRD §Subsystem 3. It lives here (not
 * in code) so it is tunable without a redeploy; Epic 04/10 will source it per policy
 * from {@code policies.reputation params} (AC 5), at which point this becomes the
 * fallback default.
 *
 * <p>{@code alpha}/{@code beta} are {@link Positive} and {@code halfLife} must be a
 * positive duration: a degenerate value (a zero/negative prior yielding NaN scores, or
 * a non-positive half-life dividing by zero) fails startup loudly rather than at
 * runtime.
 *
 * @param alpha    prior pseudo-count of good observations
 * @param beta     prior pseudo-count of bad observations
 * @param halfLife age at which an event's weight halves under read-time decay
 */
@Validated
@ConfigurationProperties(prefix = "antispam.reputation")
public record ReputationProperties(
        @Positive double alpha,
        @Positive double beta,
        Duration halfLife) {

    public ReputationProperties {
        if (halfLife == null || halfLife.isZero() || halfLife.isNegative()) {
            throw new IllegalArgumentException(
                    "antispam.reputation.half-life must be a positive duration: " + halfLife);
        }
    }

    /**
     * The read-time decay model derived from {@link #halfLife()} — the fade applied to
     * each event's weight when a sender's reputation is recomputed from the log.
     */
    public ExponentialDecay decay() {
        return new ExponentialDecay(halfLife);
    }
}
