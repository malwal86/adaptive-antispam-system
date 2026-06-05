package com.antispam.decision.hardrule;

import com.antispam.ingest.Email;
import java.util.Optional;

/**
 * A single cheap, explainable check that can decisively override the model. Each
 * rule inspects an email and either fires (returning a {@link RuleMatch}) or
 * stays silent (returning {@link Optional#empty()}). Rules must be side-effect
 * free and fast: they run on every message before any model or LLM, on the
 * synchronous fast path.
 *
 * <p>A rule's <em>data</em> (denylists, the brand list) is configuration, not
 * code (see {@link HardRuleProperties}); a rule's <em>logic</em> (how to test an
 * email against that data) lives in its implementation. Evaluation order across
 * rules is fixed with {@link org.springframework.core.annotation.Order @Order}
 * so the accumulated reason codes are deterministic.
 */
public interface HardRule {

    /**
     * @return a match if this rule fires on {@code email}, otherwise empty
     */
    Optional<RuleMatch> evaluate(Email email);
}
