package com.antispam.decision.hardrule;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.TestEmails;
import com.antispam.ingest.Email;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Merge behaviour over fake rules, isolated from the real rule logic. */
class HardRuleEngineTest {

    private static final Email ANY = TestEmails.bodyContaining("anything");

    private static HardRule fires(Decision decision, ReasonCode reasonCode) {
        return email -> Optional.of(new RuleMatch(decision, reasonCode));
    }

    private static HardRule silent() {
        return email -> Optional.empty();
    }

    @Test
    void reports_no_override_when_no_rule_fires() {
        var engine = new HardRuleEngine(List.of(silent(), silent()));

        assertThat(engine.evaluate(ANY)).isEmpty();
    }

    @Test
    void a_single_match_yields_a_hard_rule_outcome() {
        var engine = new HardRuleEngine(List.of(silent(), fires(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL)));

        DecisionOutcome outcome = engine.evaluate(ANY).orElseThrow();
        assertThat(outcome.decision()).isEqualTo(Decision.BLOCK);
        assertThat(outcome.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
        assertThat(outcome.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(outcome.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void most_severe_decision_wins_and_all_codes_are_recorded_in_order() {
        var engine = new HardRuleEngine(List.of(
                fires(Decision.QUARANTINE, ReasonCode.MALFORMED_AUTH_BRAND_SPOOF),
                fires(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL)));

        DecisionOutcome outcome = engine.evaluate(ANY).orElseThrow();
        assertThat(outcome.decision()).isEqualTo(Decision.BLOCK);
        assertThat(outcome.reasonCodes())
                .containsExactly(ReasonCode.MALFORMED_AUTH_BRAND_SPOOF, ReasonCode.KNOWN_BAD_URL);
    }

    @Test
    void duplicate_reason_codes_are_recorded_only_once() {
        var engine = new HardRuleEngine(List.of(
                fires(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL),
                fires(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL)));

        assertThat(engine.evaluate(ANY).orElseThrow().reasonCodes())
                .containsExactly(ReasonCode.KNOWN_BAD_URL);
    }
}
