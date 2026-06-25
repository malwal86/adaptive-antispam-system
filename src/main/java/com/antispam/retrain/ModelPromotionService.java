package com.antispam.retrain;

import com.antispam.decision.model.ModelArtifactStore;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Promotes a gated retrain candidate live, and rolls back, by flipping the active flag in
 * {@code policies} (PRD §Subsystem 9 steps 4–5, story 10.04). Promotion is the deliberate, audited act
 * that turns a passed candidate into the served model; rollback is the same flip in reverse. Both are
 * flag changes — never a per-email hot-swap — so the actual model change is observed lazily by
 * {@code ServedModel}/{@code ModelRegistry} on the next decision.
 *
 * <p><b>The gate is re-checked here, not trusted from the caller.</b> {@link #promote} grades the run
 * through the {@link PrecisionGateService} itself and refuses to flip a candidate that did not pass —
 * the single safety invariant of the whole loop, because a false promotion serves an unvetted model to
 * live traffic. It also confirms the candidate's artifact is actually fetchable before flipping, so an
 * activation never points the serving path at a model that was never staged.
 *
 * <p>Promotion is idempotent on the registry side (the upsert) and the activation is atomic (the
 * one-active index), so re-running a promotion for the same passed run is safe.
 */
@Service
public class ModelPromotionService {

    private static final Logger log = LoggerFactory.getLogger(ModelPromotionService.class);

    private static final String ARTIFACT_URI_FORMAT = "candidates/%s/spam-classifier-%s.onnx";

    private final PrecisionGateService gateService;
    private final PolicyRepository policies;
    private final ModelArtifactStore artifactStore;
    private final ModelVersionRepository modelVersions;
    private final ModelActivationAuditRepository audit;

    @Autowired
    public ModelPromotionService(
            PrecisionGateService gateService,
            PolicyRepository policies,
            ModelArtifactStore artifactStore,
            ModelVersionRepository modelVersions,
            ModelActivationAuditRepository audit) {
        this.gateService = gateService;
        this.policies = policies;
        this.artifactStore = artifactStore;
        this.modelVersions = modelVersions;
        this.audit = audit;
    }

    /**
     * Promotes the candidate scored by {@code runId} live: re-checks the precision gate, registers the
     * model_version, and flips its policy active (prior active → false).
     *
     * @param runId the replay run the candidate was scored under
     * @param by    the actor performing the promotion (for audit)
     * @return what is now served and what was active before
     * @throws IllegalStateException          if the candidate did not pass the gate, or the gate could
     *                                        not resolve the model the candidate policy is calibrated for
     * @throws com.antispam.decision.model.ModelArtifactNotFoundException if the candidate's artifact is
     *                                        not fetchable from any store (it was never staged)
     */
    public PromotionResult promote(UUID runId, String by) {
        GateResult gate = gateService.evaluate(runId);
        if (!gate.passed()) {
            throw new IllegalStateException(String.format(
                    "candidate for run %s did not pass the precision gate (precision=%.4f floor=%.4f) "
                            + "— refusing to promote: %s",
                    runId, gate.precision(), gate.precisionFloor(), gate.reason()));
        }
        String modelVersion = gate.modelVersion();
        if (modelVersion == null) {
            throw new IllegalStateException(
                    "gate for run " + runId + " has no model_version (its policy " + gate.policyVersion()
                            + " is unknown) — refusing to promote");
        }

        // Fail before flipping anything if the artifact the activation would point at is not fetchable.
        artifactStore.modelBytes(modelVersion);

        String priorPolicy = policies.findActive().map(Policy::version).orElse(null);

        String artifactUri = String.format(ARTIFACT_URI_FORMAT, modelVersion, modelVersion);
        ModelVersionRecord registered = modelVersions.register(new ModelVersionRecord(
                modelVersion, artifactUri, gate.precision(), runId, by, null));

        policies.activate(gate.policyVersion());
        audit.record(ModelActivationAction.PROMOTE, gate.policyVersion(), modelVersion, by);

        log.info("promoted model={} policy={} (prior={}) precision={} by={}",
                modelVersion, gate.policyVersion(), priorPolicy, gate.precision(), by);
        return new PromotionResult(modelVersion, gate.policyVersion(), priorPolicy,
                gate.precision(), registered.promotedAt(), by);
    }

    /**
     * Rolls back to {@code toPolicyVersion}: reactivates that policy so its model is served again on
     * the next decision.
     *
     * @param toPolicyVersion the policy to reactivate
     * @param by              the actor performing the rollback (for audit)
     * @return what is now served and what was active before the rollback
     * @throws IllegalArgumentException if no policy has that version
     */
    public RollbackResult rollback(String toPolicyVersion, String by) {
        Policy target = policies.findByVersion(toPolicyVersion).orElseThrow(() ->
                new IllegalArgumentException("no policy to roll back to with version " + toPolicyVersion));
        String priorPolicy = policies.findActive().map(Policy::version).orElse(null);

        policies.activate(toPolicyVersion);
        ModelActivationAudit entry = audit.record(
                ModelActivationAction.ROLLBACK, toPolicyVersion, target.modelVersion(), by);

        log.info("rolled back to policy={} model={} (prior={}) by={}",
                toPolicyVersion, target.modelVersion(), priorPolicy, by);
        return new RollbackResult(toPolicyVersion, target.modelVersion(), priorPolicy, entry.at(), by);
    }
}
