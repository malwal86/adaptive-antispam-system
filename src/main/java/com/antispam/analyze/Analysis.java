package com.antispam.analyze;

import com.antispam.decision.Classification;

/**
 * Result of an analyse request: the persisted {@link Classification} plus whether
 * the analysed email was already present (so the UI can say "re-analysed" rather
 * than "ingested"). Pairs the durable decision with the one transient fact about
 * the request that the response needs.
 *
 * @param classification the persisted decision
 * @param duplicate      true when the email already existed (duplicate bytes, or
 *                       analysed by id) — no new canonical row was created
 */
public record Analysis(Classification classification, boolean duplicate) {
}
