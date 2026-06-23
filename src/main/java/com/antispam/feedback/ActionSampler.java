package com.antispam.feedback;

import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.random.RandomGenerator;
import org.springframework.stereotype.Component;

/**
 * Samples a synthetic user's action as {@code f(ground_truth, decision_shown, persona_biases)}
 * (story 07.02, PRD §Subsystem 7). The action space is conditioned on what the filter did
 * ({@link FeedbackAction#spaceFor}); within it, each action gets a weight built from the email's
 * ground truth and the persona's biases, the weights are normalized to a distribution, and one
 * action is drawn from the supplied {@link RandomGenerator}. Sampling from an injected RNG (rather
 * than a private one) is what makes a whole run reproducible from a seed (AC 5).
 *
 * <p>The weights encode the realistic, truth-conditioned behavior:
 * <ul>
 *   <li><b>REPORT</b> (delivered) scales with {@code reportBias}, and users mostly report mail that
 *       really is spam/phish — misreporting legitimate mail is rare.</li>
 *   <li><b>CLICK</b> (delivered) scales with {@code clickBias}; on bad mail it is gated by
 *       {@code riskTolerance} (clicking a risky link is "falling for it"), on good mail it is plain
 *       engagement.</li>
 *   <li><b>RESCUE</b> (withheld) is high for a wrongly-withheld <em>ham</em> false positive and low
 *       for actual spam, scaled by {@code riskTolerance} (willingness to override the filter).</li>
 *   <li><b>IGNORE</b> always carries the residual probability mass, so it is reachable and the
 *       distribution is proper whatever the biases are.</li>
 * </ul>
 *
 * <p>This is the good-faith, truth-conditioned baseline; the malicious channel attacks
 * (report/rescue bombers that act <em>against</em> ground truth) are layered on in story 07.04.
 */
@Component
public class ActionSampler {

    /** Coarse upper bound on how long a user takes to act, in seconds (24h). */
    static final long MAX_DELAY_SECONDS = 86_400L;

    /** Floor on IGNORE's mass so doing nothing is always a possible outcome. */
    private static final double MIN_IGNORE_WEIGHT = 0.05;

    /**
     * Samples one action for {@code persona} reacting to {@code email}, drawing from {@code rng}.
     * Draws are taken in a fixed order — the categorical action first, then the delay — so the same
     * seeded RNG, advanced the same way, reproduces the same stream.
     */
    public SampledAction sample(DecidedEmail email, Persona persona, RandomGenerator rng) {
        List<FeedbackAction> space = FeedbackAction.spaceFor(email.decisionShown());
        double[] weights = weights(space, email.groundTruth(), persona);

        double total = 0.0;
        for (double weight : weights) {
            total += weight;
        }
        int chosen = sampleIndex(weights, total, rng.nextDouble());

        FeedbackAction action = space.get(chosen);
        double confidence = weights[chosen] / total;
        long delaySeconds = Math.round(rng.nextDouble() * MAX_DELAY_SECONDS);
        return new SampledAction(action, confidence, delaySeconds);
    }

    private static double[] weights(List<FeedbackAction> space, GroundTruthLabel truth, Persona persona) {
        boolean bad = truth != GroundTruthLabel.HAM; // SPAM or PHISH
        double[] weights = new double[space.size()];
        for (int i = 0; i < space.size(); i++) {
            weights[i] = switch (space.get(i)) {
                case REPORT -> persona.reportBias() * (bad ? 0.9 : 0.1);
                case CLICK -> persona.clickBias() * (bad ? persona.riskTolerance() : 0.7);
                case RESCUE -> (bad ? 0.1 : 0.8) * (0.3 + 0.7 * persona.riskTolerance());
                case IGNORE -> 0.0; // assigned the residual mass below
            };
        }
        double engaged = 0.0;
        for (double weight : weights) {
            engaged += weight;
        }
        weights[space.indexOf(FeedbackAction.IGNORE)] = Math.max(MIN_IGNORE_WEIGHT, 1.0 - engaged);
        return weights;
    }

    /** Inverse-CDF draw over {@code weights} (which need not sum to 1); {@code u} is in [0,1). */
    private static int sampleIndex(double[] weights, double total, double u) {
        double target = u * total;
        double cumulative = 0.0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (target < cumulative) {
                return i;
            }
        }
        return weights.length - 1; // floating-point guard: u≈1 lands on the last action
    }
}
