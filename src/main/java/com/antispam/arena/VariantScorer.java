package com.antispam.arena;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.ExperimentContext;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;

/**
 * Scores an arena variant under a policy and reports whether that policy would deliver it to the inbox
 * — the one operation the arena does to a variant, shared by the bounded loop's current defender
 * ({@link AttackLoopService}) and bypass measurement's fixed baseline ({@link BypassMeasurementService})
 * so the rule lives in one place rather than mirrored in each.
 *
 * <p>Scoring is read-only ({@link ExperimentContext#callReadOnly}, story 09.03 isolation): a variant is
 * scored through the same pipeline as real mail, but a stray live-state write would be blocked, so a run
 * reads live reputation and policies yet never enforces a decision or accrues reputation.
 */
final class VariantScorer {

    private VariantScorer() {
    }

    /**
     * Whether {@code policy} would deliver {@code variant} to the inbox — the verdict
     * {@link com.antispam.decision.Decision#delivers() delivering} it (allow or warn). What delivery
     * <em>means</em> for the attacker depends on the variant's {@link Track} (a bypass for abuse, the
     * absence of a false positive for legit mail); that interpretation lives in {@link Track}, not here.
     */
    static boolean delivers(EmailRepository emails, PolicyScorer scorer, AdversarialEmail variant,
            Policy policy) {
        Email email = emails.findById(variant.variantEmailId()).orElseThrow(() ->
                new IllegalStateException("variant email vanished: " + variant.variantEmailId()));
        ScoredDecision scored = ExperimentContext.callReadOnly(() -> scorer.score(email, policy));
        return scored.decision().delivers();
    }
}
