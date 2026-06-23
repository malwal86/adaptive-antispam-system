package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.hardrule.HardRuleCircuitBreaker;
import com.antispam.decision.llm.LlmVerdict.Verdict;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The quarantine-pending resolution state machine (story 05.06), exhaustively. A successful verdict
 * promotes-to-inbox or confirms-spam; a degraded outcome fail-degrades to a conservative withholding
 * tier under the hard-rule floor. The central invariant — a resolution never delivers a message that
 * a hard rule (or conservatism) says should be withheld, so it can never set up a deliver-then-
 * retract — is checked across every combination of verdict, fast-path tier, and hard-rule floor.
 */
class PendingResolutionTest {

    private final HardRuleCircuitBreaker breaker = new HardRuleCircuitBreaker();
    private final UUID emailId = UUID.randomUUID();

    private static LlmOutcome verdict(Verdict v) {
        LlmVerdict verdict = new LlmVerdict(v, 0.9, 0.1, List.of(ReasonCode.BENIGN_CONTENT), "x");
        return LlmOutcome.valid(verdict, 10L, new BigDecimal("0.001"), 1);
    }

    @Test
    void a_legitimate_verdict_promotes_to_inbox() {
        ResolvedDecision resolved = PendingResolution.resolve(
                verdict(Verdict.LEGITIMATE), Decision.QUARANTINE, Decision.ALLOW, emailId, breaker);

        assertThat(resolved.state()).isEqualTo(ResolutionState.PROMOTED);
        assertThat(resolved.decision()).isEqualTo(Decision.ALLOW);
        assertThat(resolved.deliversToInbox()).isTrue();
        assertThat(resolved.degradedBanner()).isFalse();
    }

    @Test
    void a_spam_verdict_confirms_to_quarantine() {
        ResolvedDecision resolved = PendingResolution.resolve(
                verdict(Verdict.SPAM), Decision.QUARANTINE, Decision.ALLOW, emailId, breaker);

        assertThat(resolved.state()).isEqualTo(ResolutionState.CONFIRMED);
        assertThat(resolved.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(resolved.deliversToInbox()).isFalse();
    }

    @Test
    void a_phishing_verdict_confirms_to_block() {
        ResolvedDecision resolved = PendingResolution.resolve(
                verdict(Verdict.PHISHING), Decision.QUARANTINE, Decision.ALLOW, emailId, breaker);

        assertThat(resolved.state()).isEqualTo(ResolutionState.CONFIRMED);
        assertThat(resolved.decision()).isEqualTo(Decision.BLOCK);
    }

    @Test
    void a_degraded_outcome_fails_to_a_conservative_withholding_tier_with_the_banner() {
        // Fast-path tier was a deliverable ALLOW, but a degrade biases conservative: withhold.
        ResolvedDecision resolved = PendingResolution.resolve(
                LlmOutcome.notAttempted(), Decision.ALLOW, Decision.ALLOW, emailId, breaker);

        assertThat(resolved.state()).isEqualTo(ResolutionState.DEGRADED);
        assertThat(resolved.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(resolved.deliversToInbox()).isFalse();
        assertThat(resolved.degradedBanner()).isTrue();
    }

    @Test
    void a_degraded_outcome_keeps_a_more_severe_fast_path_tier() {
        // Conservative bias is a floor, not a reset: a BLOCK fast-path tier is preserved.
        ResolvedDecision resolved = PendingResolution.resolve(
                LlmOutcome.notAttempted(), Decision.BLOCK, Decision.ALLOW, emailId, breaker);

        assertThat(resolved.decision()).isEqualTo(Decision.BLOCK);
        assertThat(resolved.state()).isEqualTo(ResolutionState.DEGRADED);
    }

    @Test
    void a_legitimate_verdict_cannot_promote_past_a_hard_rule_floor() {
        // The circuit breaker (05.05): even a "legitimate" verdict cannot soften a hard-rule BLOCK.
        ResolvedDecision resolved = PendingResolution.resolve(
                verdict(Verdict.LEGITIMATE), Decision.QUARANTINE, Decision.BLOCK, emailId, breaker);

        assertThat(resolved.decision()).isEqualTo(Decision.BLOCK);
        assertThat(resolved.state()).isEqualTo(ResolutionState.CONFIRMED); // not promoted
        assertThat(resolved.deliversToInbox()).isFalse();
    }

    @Test
    void no_resolution_over_any_combination_can_deliver_below_the_hard_rule_floor() {
        // The fuzz invariant: across every verdict/degrade × fast-path tier × hard-rule floor, the
        // resolved decision is never below the hard-rule floor, and a delivered (promoted) outcome
        // only ever happens when the final decision genuinely delivers — never a retraction.
        List<LlmOutcome> outcomes = List.of(
                verdict(Verdict.LEGITIMATE), verdict(Verdict.SPAM), verdict(Verdict.PHISHING),
                LlmOutcome.notAttempted());
        for (LlmOutcome outcome : outcomes) {
            for (Decision fastPath : Decision.values()) {
                for (Decision floor : Decision.values()) {
                    ResolvedDecision resolved =
                            PendingResolution.resolve(outcome, fastPath, floor, emailId, breaker);

                    assertThat(resolved.decision().compareTo(floor))
                            .as("resolved %s must be >= hard-rule floor %s", resolved.decision(), floor)
                            .isGreaterThanOrEqualTo(0);
                    assertThat(resolved.state()).isNotEqualTo(ResolutionState.PENDING);
                    if (resolved.deliversToInbox()) {
                        assertThat(resolved.decision().delivers()).isTrue();
                        assertThat(resolved.state()).isEqualTo(ResolutionState.PROMOTED);
                    }
                    if (resolved.state() == ResolutionState.DEGRADED) {
                        // Degrade is always conservative: it never delivers.
                        assertThat(resolved.decision().delivers()).isFalse();
                    }
                }
            }
        }
    }
}
