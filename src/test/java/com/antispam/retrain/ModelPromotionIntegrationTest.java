package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.model.ModelFeatureVector;
import com.antispam.decision.model.OnnxModel;
import com.antispam.decision.model.ServedModel;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Proves promotion + rollback end-to-end against the real database (story 10.04 AC 1/2/3): a gated
 * candidate is registered and its policy flipped live, the serving path then resolves and serves the
 * promoted model_version, and a rollback flips the prior policy back so serving reverts — all without
 * a redeploy, all auditable.
 *
 * <p>The precision gate is mocked to a passing verdict (its real golden-set arithmetic is proven in
 * {@link PrecisionGateIntegrationTest}); this test's job is the registry + flag-flip + serve + rollback
 * contract that 10.04 adds. The candidate's artifact is a real ONNX checked into the <em>test</em>
 * classpath ({@code spam-classifier-candidate-it-v1.onnx}), so the whole real store→registry→serve path
 * runs unmocked — the test classpath standing in for Supabase, keeping remote storage out of CI.
 *
 * <p>Per the shared-DB discipline, the active policy is restored to the bootstrap regime after each
 * test so other integration classes sharing the container are unaffected. Skips without Docker.
 */
class ModelPromotionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final UUID RUN = UUID.fromString("0a0a0a0a-0000-0000-0000-00000000aaaa");
    private static final String BOOTSTRAP_POLICY = "bootstrap-v1";
    private static final String CANDIDATE_POLICY = "candidate-it-v1-policy";
    private static final String CANDIDATE_MODEL = "candidate-it-v1";

    @Autowired
    private ModelPromotionService promotionService;

    @Autowired
    private PolicyRepository policies;

    @Autowired
    private ModelVersionRepository modelVersions;

    @Autowired
    private ModelActivationAuditRepository audit;

    @Autowired
    private ServedModel servedModel;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private PrecisionGateService gateService;

    @BeforeEach
    void setUp() {
        // A gated, passing candidate scored under a candidate policy calibrated for a new model. The
        // candidate's ONNX is on the test classpath, so the real artifact store resolves it.
        when(gateService.evaluate(RUN)).thenReturn(new GateResult(
                true, 0.99, 0.95, 200, CANDIDATE_POLICY, CANDIDATE_MODEL, null, "cleared the floor"));

        // The candidate policy pre-exists (a replay only runs under an existing policy); insert it
        // inactive so the bootstrap regime stays the one active until we promote.
        if (policies.findByVersion(CANDIDATE_POLICY).isEmpty()) {
            policies.save(new Policy(CANDIDATE_POLICY, false, 0.5, 0.8, 0.95, 0.4, 0.05, 5,
                    CANDIDATE_MODEL, null));
        }
    }

    @AfterEach
    void restoreBootstrapActive() {
        policies.activate(BOOTSTRAP_POLICY);
    }

    @Test
    void promotes_serves_and_rolls_back_end_to_end() {
        float[] vector = new float[ModelFeatureVector.FEATURE_COUNT];

        // --- AC 1: promote registers the model_version and flips the candidate policy active ---
        PromotionResult promotion = promotionService.promote(RUN, "tester");

        assertThat(promotion.modelVersion()).isEqualTo(CANDIDATE_MODEL);
        assertThat(promotion.activePolicyVersion()).isEqualTo(CANDIDATE_POLICY);
        assertThat(promotion.priorPolicyVersion()).isEqualTo(BOOTSTRAP_POLICY);

        assertThat(policies.findActive()).hasValueSatisfying(p ->
                assertThat(p.version()).isEqualTo(CANDIDATE_POLICY));
        assertThat(policies.findByVersion(BOOTSTRAP_POLICY)).hasValueSatisfying(p ->
                assertThat(p.active()).isFalse());
        // Exactly one active policy after the flip (the one-active invariant holds).
        assertThat(jdbc.queryForObject("select count(*) from policies where active", Integer.class))
                .isEqualTo(1);

        // The model_version is registered with its provenance (AC 1).
        assertThat(modelVersions.findByVersion(CANDIDATE_MODEL)).hasValueSatisfying(r -> {
            assertThat(r.gatePrecision()).isEqualTo(0.99);
            assertThat(r.sourceRun()).isEqualTo(RUN);
            assertThat(r.promotedBy()).isEqualTo("tester");
            assertThat(r.artifactUri())
                    .isEqualTo("candidates/candidate-it-v1/spam-classifier-candidate-it-v1.onnx");
        });
        // The promotion is audited: who/when/which (AC 5).
        assertThat(audit.recent(10)).anySatisfy(e -> {
            assertThat(e.action()).isEqualTo(ModelActivationAction.PROMOTE);
            assertThat(e.policyVersion()).isEqualTo(CANDIDATE_POLICY);
            assertThat(e.modelVersion()).isEqualTo(CANDIDATE_MODEL);
            assertThat(e.actor()).isEqualTo("tester");
        });

        // --- AC 2: the serving path now resolves and serves the promoted model_version ---
        assertThat(servedModel.activeModelVersion()).isEqualTo(CANDIDATE_MODEL);
        assertThat(servedModel.score(vector).modelVersion()).isEqualTo(CANDIDATE_MODEL);

        // --- AC 3: rollback flips the prior policy back and serving reverts ---
        RollbackResult rollback = promotionService.rollback(BOOTSTRAP_POLICY, "tester");

        assertThat(rollback.activePolicyVersion()).isEqualTo(BOOTSTRAP_POLICY);
        assertThat(rollback.priorPolicyVersion()).isEqualTo(CANDIDATE_POLICY);
        assertThat(servedModel.activeModelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
        assertThat(servedModel.score(vector).modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);

        List<ModelActivationAudit> history = audit.recent(10);
        assertThat(history).anySatisfy(e ->
                assertThat(e.action()).isEqualTo(ModelActivationAction.ROLLBACK));
    }
}
