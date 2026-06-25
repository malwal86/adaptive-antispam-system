package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.model.ModelArtifactNotFoundException;
import com.antispam.decision.model.ModelArtifactStore;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The promotion/rollback contract (story 10.04 AC 1/5), pinned without a database. The load-bearing
 * cases: a passed candidate is registered and its policy activated (prior steps down), a <b>failed</b>
 * candidate is never activated (the safety invariant), a candidate whose artifact was never staged is
 * refused before any flip, and both promote and rollback write an audit entry with who/what.
 */
@ExtendWith(MockitoExtension.class)
class ModelPromotionServiceTest {

    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final byte[] ARTIFACT = {1, 2, 3};

    @Mock
    private PrecisionGateService gateService;

    @Mock
    private PolicyRepository policies;

    @Mock
    private ModelArtifactStore artifactStore;

    @Mock
    private ModelVersionRepository modelVersions;

    @Mock
    private ModelActivationAuditRepository audit;

    private ModelPromotionService service() {
        return new ModelPromotionService(gateService, policies, artifactStore, modelVersions, audit);
    }

    private static GateResult gate(boolean passed, String policyVersion, String modelVersion) {
        return new GateResult(passed, 0.97, 0.95, 200, policyVersion, modelVersion, null,
                passed ? "cleared the floor" : "below the floor");
    }

    private static Policy policy(String version, String modelVersion) {
        return new Policy(version, true, 0.5, 0.8, 0.95, 0.4, 0.05, 5, modelVersion,
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void promotes_a_passed_candidate_by_registering_and_activating_its_policy() {
        when(gateService.evaluate(RUN)).thenReturn(gate(true, "policy-2", "model-2"));
        when(artifactStore.modelBytes("model-2")).thenReturn(ARTIFACT);
        when(policies.findActive()).thenReturn(Optional.of(policy("policy-1", "model-1")));
        when(modelVersions.register(any())).thenAnswer(inv -> {
            ModelVersionRecord r = inv.getArgument(0);
            return new ModelVersionRecord(r.version(), r.artifactUri(), r.gatePrecision(),
                    r.sourceRun(), r.promotedBy(), Instant.parse("2026-06-24T10:00:00Z"));
        });

        PromotionResult result = service().promote(RUN, "alice");

        assertThat(result.modelVersion()).isEqualTo("model-2");
        assertThat(result.activePolicyVersion()).isEqualTo("policy-2");
        assertThat(result.priorPolicyVersion()).isEqualTo("policy-1");
        assertThat(result.gatePrecision()).isEqualTo(0.97);
        assertThat(result.promotedBy()).isEqualTo("alice");
        verify(policies).activate("policy-2");
        verify(audit).record(ModelActivationAction.PROMOTE, "policy-2", "model-2", "alice");
        verify(modelVersions).register(any());
    }

    @Test
    void registers_the_artifact_uri_matching_the_staging_layout() {
        when(gateService.evaluate(RUN)).thenReturn(gate(true, "policy-2", "model-2"));
        when(artifactStore.modelBytes("model-2")).thenReturn(ARTIFACT);
        when(policies.findActive()).thenReturn(Optional.empty());
        when(modelVersions.register(any())).thenAnswer(inv -> inv.getArgument(0));

        service().promote(RUN, "alice");

        verify(modelVersions).register(org.mockito.ArgumentMatchers.argThat(r ->
                r.artifactUri().equals("candidates/model-2/spam-classifier-model-2.onnx")
                        && r.sourceRun().equals(RUN)
                        && r.version().equals("model-2")));
    }

    @Test
    void refuses_to_promote_a_candidate_that_failed_the_gate() {
        when(gateService.evaluate(RUN)).thenReturn(gate(false, "policy-2", "model-2"));

        assertThatThrownBy(() -> service().promote(RUN, "alice"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not pass the precision gate");

        // The safety invariant: nothing is flipped, registered, or audited for a failed candidate.
        verify(policies, never()).activate(any());
        verify(modelVersions, never()).register(any());
        verify(audit, never()).record(any(), any(), any(), any());
    }

    @Test
    void refuses_to_promote_when_the_candidate_artifact_was_never_staged() {
        when(gateService.evaluate(RUN)).thenReturn(gate(true, "policy-2", "model-2"));
        when(artifactStore.modelBytes("model-2"))
                .thenThrow(new ModelArtifactNotFoundException("not staged"));

        assertThatThrownBy(() -> service().promote(RUN, "alice"))
                .isInstanceOf(ModelArtifactNotFoundException.class);

        verify(policies, never()).activate(any());
        verify(modelVersions, never()).register(any());
    }

    @Test
    void rolls_back_by_reactivating_the_prior_policy_and_audits_it() {
        when(policies.findByVersion("policy-1")).thenReturn(Optional.of(policy("policy-1", "model-1")));
        when(policies.findActive()).thenReturn(Optional.of(policy("policy-2", "model-2")));
        when(audit.record(ModelActivationAction.ROLLBACK, "policy-1", "model-1", "bob"))
                .thenReturn(new ModelActivationAudit(UUID.randomUUID(), ModelActivationAction.ROLLBACK,
                        "policy-1", "model-1", "bob", Instant.parse("2026-06-24T11:00:00Z")));

        RollbackResult result = service().rollback("policy-1", "bob");

        assertThat(result.activePolicyVersion()).isEqualTo("policy-1");
        assertThat(result.modelVersion()).isEqualTo("model-1");
        assertThat(result.priorPolicyVersion()).isEqualTo("policy-2");
        assertThat(result.rolledBackBy()).isEqualTo("bob");
        verify(policies).activate("policy-1");
    }

    @Test
    void refuses_to_roll_back_to_an_unknown_policy() {
        when(policies.findByVersion("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().rollback("ghost", "bob"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ghost");

        verify(policies, never()).activate(any());
    }
}
