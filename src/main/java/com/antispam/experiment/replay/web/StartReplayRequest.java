package com.antispam.experiment.replay.web;

/**
 * The body of {@code POST /replays}: which policy to score the replayed corpus under. The policy
 * must already exist; an unknown version is rejected with 400 (see {@link ReplayExceptionHandler}).
 *
 * @param policyVersion the {@code policies.version} every replayed email is scored under
 */
public record StartReplayRequest(String policyVersion) {
}
