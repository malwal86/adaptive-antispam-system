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
     * Decides {@code email} and records the decision.
     *
     * @return the persisted {@link Classification}
     */
    public Classification decide(Email email) {
        DecisionOutcome outcome = hardRuleEngine.evaluate(email)
                .orElseGet(() -> contentClassifier.classify(email));
        Classification classification = repository.save(email.id(), outcome);
        // No PII here: only the email id, route, verdict, and reason codes.
        log.info("decided email={} route={} decision={} reasons={} latencyMs={}",
                email.id(), outcome.route(), outcome.decision(),
                outcome.reasonCodes(), outcome.latencyMs());
        return classification;
    }
}
