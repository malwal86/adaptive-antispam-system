package com.antispam.retrain;

import java.time.Instant;
import java.util.UUID;

/**
 * One entry in the promotion/rollback audit log (story 10.04 AC 5): a record that a retrain activation
 * change happened — who flipped the active policy, to which policy/model, whether it was a promotion or
 * a rollback, and when. A rollback is its own row pointing at the prior policy, never an edit of an
 * earlier entry, so the log reads as a truthful history of what was served when.
 *
 * @param id            the entry id
 * @param action        whether this flip promoted a candidate or rolled back to a prior policy
 * @param policyVersion the policy whose active flag the action set
 * @param modelVersion  the model that policy is calibrated for (denormalized for standalone reads)
 * @param actor         who performed the action
 * @param at            when it happened (assigned by the database)
 */
public record ModelActivationAudit(
        UUID id,
        ModelActivationAction action,
        String policyVersion,
        String modelVersion,
        String actor,
        Instant at) {
}
