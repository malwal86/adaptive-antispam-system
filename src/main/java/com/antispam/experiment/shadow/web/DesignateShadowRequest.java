package com.antispam.experiment.shadow.web;

/**
 * The body of {@code POST /shadow/policy}: which policy to designate as the shadow (logged-only)
 * regime. The version must exist; an unknown one is rejected with 400 (see
 * {@link ShadowExceptionHandler}).
 *
 * @param version the {@code policies.version} to shadow against live traffic
 */
public record DesignateShadowRequest(String version) {
}
