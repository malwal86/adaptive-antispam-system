package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.ingest.Email;
import java.util.Optional;

/**
 * The seam by which burst/campaign detection escalates a decision regardless of the
 * posterior (PRD §Subsystem 1 step 4). A sudden blast of individually-innocuous-looking
 * mail is invisible to a per-message score but obvious in aggregate; this hook lets that
 * aggregate signal override the score-derived tier.
 *
 * <p><b>Implemented in Epic 06.</b> Story 04.05 defined the seam and wired the pipeline to
 * honour it; story 06.01 fills it with the Redis sliding-window detector ({@link RedisBurstOverride}),
 * selected when {@code antispam.burst.enabled=true}. When burst detection is switched off
 * {@link NoBurstOverride} is wired instead and never escalates, so the tier comes purely from the
 * policy thresholds — no pipeline change either way, because the contract already exists.
 */
public interface BurstOverride {

    /**
     * An instruction to escalate a decision to {@code tier}, with the {@code reason} to
     * record for it.
     *
     * @param tier   the floor tier to escalate to; the final decision is the more severe
     *               of this and the posterior-derived tier, so an override can only
     *               raise severity, never lower it
     * @param reason the reason code documenting the override
     */
    record Escalation(Decision tier, ReasonCode reason) {
    }

    /**
     * Whether {@code email} belongs to a burst that warrants escalation under {@code policy}.
     *
     * @param email  the email being decided
     * @param policy the active decision regime, whose {@link Policy#burstThreshold()} the detector
     *               compares the sender's windowed velocity against — so the trigger is tunable per
     *               regime without a second policy lookup
     * @return the escalation to apply, or {@link Optional#empty()} when no burst is detected
     */
    Optional<Escalation> evaluate(Email email, Policy policy);
}
