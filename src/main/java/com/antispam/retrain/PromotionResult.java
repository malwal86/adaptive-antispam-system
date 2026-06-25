package com.antispam.retrain;

import java.time.Instant;

/**
 * The outcome of promoting a gated retrain candidate live (story 10.04): which model is now served,
 * the policy whose active flag was flipped to it, what was active before (so a rollback target is
 * obvious), and the audit stamp. Returned to the caller of the promotion endpoint as the proof the
 * flip happened.
 *
 * @param modelVersion        the model now being served
 * @param activePolicyVersion the policy now active (calibrated for {@code modelVersion})
 * @param priorPolicyVersion  the policy that was active before this promotion, or null if none
 * @param gatePrecision       the precision the candidate cleared the gate with
 * @param promotedAt          when the model_version was registered
 * @param promotedBy          the actor that promoted it
 */
public record PromotionResult(
        String modelVersion,
        String activePolicyVersion,
        String priorPolicyVersion,
        double gatePrecision,
        Instant promotedAt,
        String promotedBy) {
}
