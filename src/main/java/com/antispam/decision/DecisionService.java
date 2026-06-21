package com.antispam.decision;

import com.antispam.decision.hardrule.HardRuleEngine;
import com.antispam.ingest.Email;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The fast-path decision pipeline for a single email (story 01.04 stage). It runs
 * the hard rules first and, only if none override, falls through to the model
 * path; the resulting decision is persisted.
 *
 * <p>The short-circuit is structural, not incidental: {@code orElseGet} evaluates
 * the {@link ContentClassifier} lazily, so a hard-rule hit reaches a decision
 * without the model ever being invoked — the explicit "skip the model" guarantee
 * from PRD §Subsystem 1. Later stages (calibration, fusion, burst override, LLM
 * routing) extend this pipeline in their epics.
 *
 * <p><b>Deciding vs. persisting are separable.</b> {@link #evaluate} runs the
 * pipeline and returns the in-memory {@link DecisionOutcome} without writing a row;
 * {@link #decide} is {@code evaluate} plus persistence. The split lets a read-only
 * consumer derive a verdict for an email — e.g. reputation accrual off the event
 * spine (story 03.05) — without minting a second {@link Classification} for it.
 */
@Service
public class DecisionService {

    private static final Logger log = LoggerFactory.getLogger(DecisionService.class);

    private final HardRuleEngine hardRuleEngine;
    private final ContentClassifier contentClassifier;
    private final ClassificationRepository repository;

    @Autowired
    public DecisionService(
            HardRuleEngine hardRuleEngine,
            ContentClassifier contentClassifier,
            ClassificationRepository repository) {
        this.hardRuleEngine = hardRuleEngine;
        this.contentClassifier = contentClassifier;
        this.repository = repository;
    }

    /**
     * Runs the decision pipeline for {@code email} and returns the verdict
     * <em>without persisting it</em>: hard rules first, the model path only if none
     * override. Deterministic for a given email, so a caller can derive the verdict
     * more than once (e.g. on redelivery) and get the same answer.
     *
     * @return the in-memory {@link DecisionOutcome}
     */
    public DecisionOutcome evaluate(Email email) {
        return hardRuleEngine.evaluate(email)
                .orElseGet(() -> contentClassifier.classify(email));
    }

    /**
     * Decides {@code email} and records the decision.
     *
     * @return the persisted {@link Classification}
     */
    public Classification decide(Email email) {
        DecisionOutcome outcome = evaluate(email);
        Classification classification = repository.save(email.id(), outcome);
        // No PII here: only the email id, route, verdict, and reason codes.
        log.info("decided email={} route={} decision={} reasons={} latencyMs={}",
                email.id(), outcome.route(), outcome.decision(),
                outcome.reasonCodes(), outcome.latencyMs());
        return classification;
    }
}
