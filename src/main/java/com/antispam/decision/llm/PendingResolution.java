package com.antispam.decision.llm;

import com.antispam.decision.Decision;
import com.antispam.decision.hardrule.HardRuleCircuitBreaker;
import com.antispam.decision.llm.LlmVerdict.Verdict;
import java.util.UUID;

/**
 * The pure resolution rule (story 05.06): turn a quarantine-pending email's {@link LlmOutcome} into
 * a final {@link ResolvedDecision}, under the hard-rule circuit breaker. This is the heart of the
 * "promote-to-inbox, confirm-spam, or fail-degrade" state machine, kept side-effect-free so the
 * never-deliver-then-retract invariant can be exhaustively tested without async, persistence, or a
 * provider.
 *
 * <p><b>The invariant, by construction.</b> A pending email is <em>withheld</em>. Every resolution
 * either keeps it withheld (confirm / degrade) or delivers it via a single explicit promotion — and
 * a promotion is recorded only when the final, circuit-breaker-floored decision actually delivers.
 * There is no transition from delivered back to withheld, so a message the user has seen is never
 * retracted.
 *
 * <p><b>Mapping.</b> A successful verdict maps to a tier — LEGITIMATE→allow, SPAM→quarantine,
 * PHISHING→block — which is then floored at the hard-rule severity by
 * {@link HardRuleCircuitBreaker}: the LLM may escalate but never soften a hard-rule signal (story
 * 05.05). The state is read back from the floored decision, so a verdict the breaker overrode lands
 * as {@code CONFIRMED}, not {@code PROMOTED}. A degraded outcome — the call could not produce a
 * verdict (SLA exceeded, budget spent, provider unavailable) — fails to the fast-path posterior tier
 * with a conservative withholding bias, and raises the degraded banner.
 */
final class PendingResolution {

    private PendingResolution() {
    }

    /**
     * Resolves a pending decision.
     *
     * @param outcome       the LLM outcome (a validated verdict, or degraded)
     * @param fastPathTier  the posterior-derived tier to fall back to on a degrade
     * @param hardRuleFloor the hard-rule severity the decision may never drop below
     * @param emailId       the email being resolved, for the breaker's conflict audit trail
     * @param breaker       the hard-rule circuit breaker
     */
    static ResolvedDecision resolve(
            LlmOutcome outcome,
            Decision fastPathTier,
            Decision hardRuleFloor,
            UUID emailId,
            HardRuleCircuitBreaker breaker) {
        if (outcome.degraded()) {
            // Conservative bias: never less severe than withholding, and never below the hard-rule
            // floor. The pending email was already withheld, so this keeps it withheld — degrading
            // can never deliver, so it can never retract.
            Decision conservative = Decision.mostSevere(java.util.List.of(fastPathTier, Decision.QUARANTINE));
            Decision held = breaker.floorAtHardRule(emailId, hardRuleFloor, conservative);
            return new ResolvedDecision(held, ResolutionState.DEGRADED, true);
        }

        Decision candidate = tierFor(outcome.verdict().verdict());
        Decision held = breaker.floorAtHardRule(emailId, hardRuleFloor, candidate);
        // Promotion is delivery; record it only if the floored decision actually delivers, so a
        // verdict the breaker overrode up to a withholding tier is a confirmation, not a promotion.
        ResolutionState state = held.delivers() ? ResolutionState.PROMOTED : ResolutionState.CONFIRMED;
        return new ResolvedDecision(held, state, false);
    }

    /** The decision tier the model's categorical verdict maps to before the hard-rule floor. */
    private static Decision tierFor(Verdict verdict) {
        return switch (verdict) {
            case LEGITIMATE -> Decision.ALLOW;
            case SPAM -> Decision.QUARANTINE;
            case PHISHING -> Decision.BLOCK;
        };
    }
}
