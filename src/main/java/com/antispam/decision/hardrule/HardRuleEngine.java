package com.antispam.decision.hardrule;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.ReasonCode;
import com.antispam.decision.RouteUsed;
import com.antispam.ingest.Email;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs every {@link HardRule} over an email and merges their verdicts into a
 * single override — or reports that no rule fired and the message should continue
 * to the model path.
 *
 * <p>Merge semantics (acceptance criteria of story 01.04): when several rules
 * fire, the <strong>most severe</strong> decision wins, and <strong>all</strong>
 * matched reason codes are recorded for explainability. Rules are injected in
 * {@code @Order} order, so the recorded codes are deterministic.
 */
@Component
public class HardRuleEngine {

    private final List<HardRule> rules;

    @Autowired
    public HardRuleEngine(List<HardRule> rules) {
        this.rules = List.copyOf(rules);
    }

    /**
     * Evaluates all hard rules against {@code email}.
     *
     * @return an override outcome (route {@link RouteUsed#HARD_RULE}) if any rule
     *     fired, otherwise {@link Optional#empty()} meaning "no override — defer
     *     to the model". The outcome's {@code latencyMs} is the time spent
     *     evaluating the rules.
     */
    public Optional<DecisionOutcome> evaluate(Email email) {
        long startNanos = System.nanoTime();
        List<RuleMatch> matches = new ArrayList<>();
        for (HardRule rule : rules) {
            rule.evaluate(email).ifPresent(matches::add);
        }
        long latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        if (matches.isEmpty()) {
            return Optional.empty();
        }
        Decision decision = Decision.mostSevere(matches.stream().map(RuleMatch::decision).toList());
        List<ReasonCode> reasonCodes = matches.stream()
                .map(RuleMatch::reasonCode)
                .distinct()
                .toList();
        return Optional.of(new DecisionOutcome(decision, reasonCodes, RouteUsed.HARD_RULE, latencyMs));
    }
}
