package com.antispam.experiment.shadow;

import com.antispam.decision.policy.ScoredDecision;
import java.time.Instant;
import java.util.UUID;

/**
 * One stored row of live shadow scoring (story 09.02): for a single live email, the verdict the
 * active (enforced) policy assigned and the verdict the shadow (logged-only) policy would assign,
 * with their {@link ShadowDiff}. Both sides are {@link ScoredDecision}s carrying their own policy
 * version, so a row is self-describing — readable as promotion evidence without re-joining.
 *
 * @param id        the row's generated identifier
 * @param emailId   the live email both policies scored
 * @param active    the active policy's verdict (the one that was enforced)
 * @param shadow    the shadow policy's verdict (logged-only, never enforced)
 * @param diff      how the two verdicts compare
 * @param createdAt when the row was written
 */
public record ShadowDecision(
        UUID id, UUID emailId, ScoredDecision active, ScoredDecision shadow, ShadowDiff diff,
        Instant createdAt) {
}
