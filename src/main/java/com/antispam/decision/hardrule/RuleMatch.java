package com.antispam.decision.hardrule;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;

/**
 * One hard rule's verdict when it fires: the decision it demands and the single
 * reason code that explains it. A rule that does not fire returns no match (an
 * empty {@link java.util.Optional}); the {@link HardRuleEngine} merges the
 * matches from all rules.
 *
 * @param decision   the verdict this rule demands (hard rules emit
 *                   {@link Decision#QUARANTINE} or {@link Decision#BLOCK})
 * @param reasonCode why the rule fired
 */
public record RuleMatch(Decision decision, ReasonCode reasonCode) {
}
