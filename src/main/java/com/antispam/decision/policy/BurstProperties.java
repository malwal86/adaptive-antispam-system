package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The runtime burst detector's knobs (story 06.01), bound from {@code antispam.burst}. The
 * <em>threshold</em> — how many messages in the window constitute a burst — lives on the active
 * {@link Policy} so it is versioned and comparable per regime; what lives here is the detector
 * mechanism that is the same across regimes: the window length over which velocity is measured and
 * the tier a detected burst escalates to. Off by default so local/dev and the Postgres-only tests
 * wire {@link NoBurstOverride} and never dial Redis; the hosted deploy sets
 * {@code ANTISPAM_BURST_ENABLED=true} alongside {@code APP_REDIS_URL}.
 *
 * @param enabled    whether burst detection runs at all. Off by default — see above
 * @param window     the trailing sliding window over which a sender's message velocity is counted;
 *                   the count within it is what {@link Policy#burstThreshold()} is compared against.
 *                   Must be positive
 * @param escalateTo the floor tier a detected burst escalates to. The override can only raise
 *                   severity ({@link PolicyDecisionService} takes the more severe of this and the
 *                   posterior-derived tier), so a blast is escalated to at least this tier regardless
 *                   of posterior. Must be more severe than {@link Decision#ALLOW}, else it could
 *                   never change a tier
 */
@Validated
@ConfigurationProperties(prefix = "antispam.burst")
public record BurstProperties(boolean enabled, Duration window, Decision escalateTo) {

    public BurstProperties {
        if (enabled) {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException(
                        "antispam.burst.window must be a positive duration but was " + window);
            }
            if (escalateTo == null || escalateTo == Decision.ALLOW) {
                throw new IllegalArgumentException(
                        "antispam.burst.escalate-to must be a tier more severe than ALLOW but was "
                                + escalateTo);
            }
        }
    }
}
