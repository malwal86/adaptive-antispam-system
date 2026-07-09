package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.hardrule.HardRuleProperties;
import com.antispam.decision.hardrule.KnownBadUrlRule;
import com.antispam.decision.hardrule.RuleMatch;
import com.antispam.ingest.Email;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Proves the everyday-inbox scenario's determinism where it matters most for the demo: the two
 * flagrant scams link to denylisted hosts, so {@link KnownBadUrlRule} blocks them <em>outright</em> —
 * no model, no LLM, no quarantine-pending dwell — while the good mail and the one borderline notice
 * are left for the model/router. The rule is configured with exactly the hosts the scenario uses
 * (the same strings listed under {@code antispam.hard-rules.url-denylist} in {@code application.yml}),
 * so this pins the scenario-bytes ↔ denylist contract without standing up Spring.
 */
class NormalMorningHardRuleTest {

    private static final RuleMatch BLOCKED = new RuleMatch(Decision.BLOCK, ReasonCode.KNOWN_BAD_URL);

    private static final KnownBadUrlRule RULE = new KnownBadUrlRule(new HardRuleProperties(
            List.of(NormalMorningScenario.BANK_SCAM_HOST, NormalMorningScenario.PRIZE_SCAM_HOST), List.of()));

    private static Optional<RuleMatch> evaluate(ScenarioEmail email) {
        Email stored = new Email(UUID.randomUUID(), new byte[32], email.raw(), null, email.source(),
                Instant.parse("2026-06-25T08:00:00Z"));
        return RULE.evaluate(stored);
    }

    @Test
    void the_two_flagrant_scams_are_hard_ruled_to_block() {
        List<ScenarioEmail> blocked = new NormalMorningScenario().build(1L).stream()
                .filter(e -> evaluate(e).filter(BLOCKED::equals).isPresent())
                .toList();

        assertThat(blocked).as("the fake-bank and prize scams block on their denylisted links").hasSize(2);
    }

    @Test
    void the_good_mail_and_the_borderline_notice_are_not_hard_ruled() {
        // Everything except the two denylisted scams falls through the hard rule — good mail to an
        // instant inbox decision on the model route, the borderline notice to the checked-then-decided
        // beat. Four of the six emails are not hard-ruled.
        List<ScenarioEmail> notRuled = new NormalMorningScenario().build(1L).stream()
                .filter(e -> evaluate(e).isEmpty())
                .toList();

        assertThat(notRuled).hasSize(4);
    }
}
