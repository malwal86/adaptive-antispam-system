package com.antispam.experiment.replay.web;

/**
 * The body of {@code POST /replays/ab}: the two policies to A/B over one fixed corpus. By convention
 * {@code policyVersionA} is the baseline (control) and {@code policyVersionB} the candidate
 * (challenger); every reported delta is {@code B − A}. Both must already exist — an unknown version
 * is rejected with 400 (see {@link ReplayAbExceptionHandler}).
 *
 * @param policyVersionA the baseline policy each corpus email is scored under
 * @param policyVersionB the candidate policy each corpus email is scored under
 */
public record StartAbRequest(String policyVersionA, String policyVersionB) {
}
