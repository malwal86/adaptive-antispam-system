package com.antispam.reputation;

/**
 * Which accrual bucket a reputation signal lands in, decided by the email's
 * authentication (story 03.03, PRD §Subsystem 3). Soft auth-gating splits a sender's
 * history in two so a spoofer can't inherit a warmed-up domain's trust:
 *
 * <ul>
 *   <li>{@link #AUTHENTICATED} — the signal came from DMARC-aligned mail, so it
 *       accrues at full weight and can build trust above neutral. This is the bucket a
 *       legitimate, correctly-authenticated sender earns reputation in.</li>
 *   <li>{@link #UNAUTHENTICATED} — the signal came from mail that did not prove
 *       alignment. It accrues into a <em>separate</em> bucket whose trust is capped at
 *       neutral ({@link GatedReputation}): it can lower a sender's standing but never
 *       lift it above neutral, so unauthenticated (possibly spoofed) mail is "neutral,
 *       never trusted."</li>
 * </ul>
 *
 * <p>The gate is <em>soft</em>: unauthenticated mail still accrues (it is not dropped),
 * and the authentication result is also a model feature (story 02.02), so a
 * misconfigured-but-legitimate sender can still earn trust downstream through good
 * content rather than being hard-blocked.
 */
public enum ReputationBucket {
    AUTHENTICATED,
    UNAUTHENTICATED
}
