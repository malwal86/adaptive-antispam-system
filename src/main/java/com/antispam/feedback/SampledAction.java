package com.antispam.feedback;

import com.antispam.decision.Probabilities;

/**
 * A persona's sampled response to one decided email (story 07.02): the action taken, how confident
 * the persona is in it, and how long they took to act. The confidence is the probability the
 * conditioned model assigned to the chosen action — high when the action was the obvious one for
 * that (truth, decision, bias), low when it was a coin-flip. Maps onto the {@code feedback_events}
 * {@code action_confidence} / {@code delay_seconds} columns.
 *
 * @param action       the sampled action
 * @param confidence   probability mass the sampler placed on {@code action}, in {@code [0,1]}
 * @param delaySeconds seconds the persona waited before acting; {@code >= 0}
 */
public record SampledAction(FeedbackAction action, double confidence, long delaySeconds) {

    public SampledAction {
        if (action == null) {
            throw new IllegalArgumentException("action is required");
        }
        Probabilities.requireUnit("action confidence", confidence);
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("delaySeconds must be >= 0 but was " + delaySeconds);
        }
    }
}
