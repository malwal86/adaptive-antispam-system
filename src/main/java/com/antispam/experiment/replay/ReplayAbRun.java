package com.antispam.experiment.replay;

import java.util.UUID;

/**
 * The handle a started A/B returns (story 09.04): the two replay runs the harness kicked off — one
 * per policy — over the same fixed corpus. Each scores asynchronously through the real replay path
 * (story 09.01); once both runs' decisions land, the caller polls the comparison with the two run
 * ids. {@code publishedCount} is the corpus size, identical for both runs (same corpus).
 *
 * @param runIdA         baseline run id (policy A) — pass to {@code compare} as the control
 * @param policyVersionA the baseline policy
 * @param runIdB         candidate run id (policy B) — pass to {@code compare} as the challenger
 * @param policyVersionB the candidate policy
 * @param publishedCount corpus emails published to each run's replay topic
 */
public record ReplayAbRun(
        UUID runIdA, String policyVersionA, UUID runIdB, String policyVersionB, int publishedCount) {
}
