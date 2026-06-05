package com.antispam.decision;

import com.antispam.ingest.Email;

/**
 * The model path the pipeline falls through to when no hard rule overrides. This
 * is the seam Epic 04 fills with the calibrated ONNX classifier and Bayesian
 * fusion; until then a {@link PlaceholderContentClassifier} stands in.
 *
 * <p>The seam exists now so the hard-rule short-circuit is real and testable: a
 * hard-rule hit must reach a decision <em>without</em> this method being called
 * (no model/LLM cost on the obvious cases).
 */
public interface ContentClassifier {

    /**
     * Decides an email that no hard rule overrode.
     *
     * @return the model-path outcome (route {@link RouteUsed#MODEL})
     */
    DecisionOutcome classify(Email email);
}
