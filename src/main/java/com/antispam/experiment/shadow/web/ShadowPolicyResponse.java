package com.antispam.experiment.shadow.web;

/**
 * Confirms which policy is now the shadow regime.
 *
 * @param version the designated shadow policy version
 */
public record ShadowPolicyResponse(String version) {
}
