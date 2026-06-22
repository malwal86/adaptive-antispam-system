package com.antispam.decision;

/**
 * The classifier's contribution to a decision: the raw model probabilities and the
 * version of the model that produced them. Present only on outcomes decided by the
 * {@link RouteUsed#MODEL} path — a hard-rule short-circuit never invokes the model,
 * so its {@link DecisionOutcome} carries no scores.
 *
 * <p>Story 04.01 records these on the {@code classifications} row. They are the raw,
 * <em>uncalibrated</em> model outputs; calibration into a trustworthy
 * {@code confidence} (Epic 04.02), fusion with sender reputation (04.04), and the
 * 4-tier decision policy (04.05) consume them in later stories. The scores being
 * recorded — not yet the verdict tier — is this story's deliverable.
 *
 * @param spamScore      P(spam) in {@code [0,1]} from the 3-class model
 * @param phishingScore  P(phish) in {@code [0,1]} from the 3-class model
 * @param modelVersion   identifier of the served model artifact (e.g. {@code bootstrap-v1})
 */
public record ModelScores(double spamScore, double phishingScore, String modelVersion) {
}
