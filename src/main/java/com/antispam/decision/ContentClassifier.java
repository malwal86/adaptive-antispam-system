package com.antispam.decision;

import com.antispam.ingest.Email;

/**
 * The model path the pipeline falls through to when no hard rule overrides.
 * Implemented by {@link com.antispam.decision.model.ModelContentClassifier}, which
 * serves the calibrated ONNX classifier in-process (story 04.01); Bayesian fusion
 * and the tiered policy extend that path in later stories (04.04, 04.05).
 *
 * <p>The seam keeps the hard-rule short-circuit real and testable: a hard-rule hit
 * must reach a decision <em>without</em> this method being called (no model/LLM cost
 * on the obvious cases).
 */
public interface ContentClassifier {

    /**
     * Decides an email that no hard rule overrode.
     *
     * @return the model-path outcome (route {@link RouteUsed#MODEL})
     */
    DecisionOutcome classify(Email email);
}
