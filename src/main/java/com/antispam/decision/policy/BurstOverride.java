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
 * <p><b>Stubbed here, filled in Epic 06.</b> Story 04.05 defines the seam and wires the
 * pipeline to honour it; the only implementation for now is {@link NoBurstOverride}, which
 * never escalates, so the tier comes purely from the policy thresholds. Story 06.01
 * replaces the bean with the real near-duplicate / burst detector — no pipeline change
 * needed, because the contract already exists.
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
     * Whether {@code email} belongs to a burst that warrants escalation.
     *
     * @return the escalation to apply, or {@link Optional#empty()} when no burst is detected
     */
    Optional<Escalation> evaluate(Email email);
}
