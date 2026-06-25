package com.antispam.decision.model;

import com.antispam.decision.ModelScores;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Scores the model path with whichever model the <em>active policy</em> is calibrated for (story
 * 10.04). This is the seam that makes promotion and rollback take effect without a redeploy: the served
 * model version is read from the active policy on each decision and handed to the {@link ModelRegistry},
 * so the moment a promotion (or a rollback) flips the {@code active} flag in {@code policies}, the next
 * decision resolves the new version and the registry lazy-loads it. The classifier asks this bean to
 * {@code score}, never the registry or a fixed model, directly.
 *
 * <p><b>Why read the active policy here, every decision.</b> Resolving the version live is what observes
 * a flag flip immediately — including a manual rollback done by flipping the flag in the database, not
 * just one driven through the promotion endpoint. A cached/TTL'd version would delay that observation
 * and let a decision serve the wrong model for a window; reading it live is both simpler and more
 * correct, and the active policy is a tiny indexed lookup the decision path resolves again downstream
 * anyway. There is deliberately no email- or payload-keyed path that changes the model — the version is
 * a function of policy state alone, which is the PRD's "no per-event hot-swap" non-goal.
 *
 * <p>With no active policy configured (a misconfiguration, or a bare test context) it falls back to the
 * bootstrap version so the model path still returns a coherent score rather than failing.
 */
@Component
public class ServedModel {

    private final ModelRegistry registry;
    private final PolicyRepository policies;

    @Autowired
    public ServedModel(ModelRegistry registry, PolicyRepository policies) {
        this.registry = registry;
        this.policies = policies;
    }

    /**
     * Scores one feature vector with the active policy's model, lazy-loading that model on the first
     * decision after an activation.
     *
     * @return the raw (uncalibrated) {@link ModelScores}, stamped with the served model version
     */
    public ModelScores score(float[] vector) {
        return registry.score(activeModelVersion(), vector);
    }

    /**
     * The model version currently being served: the active policy's {@code modelVersion}, or the
     * bootstrap version when no policy is active. Exposed for runtime observability (AC 5).
     */
    public String activeModelVersion() {
        return policies.findActive().map(Policy::modelVersion).orElse(OnnxModel.MODEL_VERSION);
    }
}
