package com.antispam.retrain;

import java.time.Instant;

/**
 * The outcome of a rollback (story 10.04): the prior policy reactivated, the model it restores, and
 * what was active before the rollback. Rollback is the same flag-flip mechanism as promotion, in
 * reverse — no redeploy — so its result mirrors {@link PromotionResult}.
 *
 * @param activePolicyVersion the policy now active again
 * @param modelVersion        the model that policy is calibrated for (now served)
 * @param priorPolicyVersion  the policy that was active before the rollback, or null if none
 * @param rolledBackAt        when the rollback was recorded
 * @param rolledBackBy        the actor that rolled it back
 */
public record RollbackResult(
        String activePolicyVersion,
        String modelVersion,
        String priorPolicyVersion,
        Instant rolledBackAt,
        String rolledBackBy) {
}
