package com.antispam.controls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.controls.web.BudgetCapsRequest;
import com.antispam.controls.web.BudgetView;
import com.antispam.controls.web.PolicyView;
import com.antispam.controls.web.ThresholdsRequest;
import com.antispam.decision.llm.LlmBudgetCaps;
import com.antispam.decision.llm.LlmBudgetProperties;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The left-rail reconfiguration logic (story 12.02), unit-level: switching activates the named
 * version, a threshold change mints a new policy carrying the active regime's burst/model and
 * activates it, and the budget caps are read/set live. The persistence and the live decision effect
 * are pinned end-to-end in {@code ControlsApiTest}.
 */
@ExtendWith(MockitoExtension.class)
class ControlsServiceTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);
    private static final LlmBudgetProperties BUDGET_PROPS =
            new LlmBudgetProperties(true, 1.00, 10.00, 0.01);

    @Mock
    private PolicyRepository policies;

    private ControlsService service() {
        return new ControlsService(policies, new LlmBudgetCaps(BUDGET_PROPS), BUDGET_PROPS, FIXED);
    }

    private static Policy policy(String version, boolean active) {
        return new Policy(version, active, 0.30, 0.60, 0.85, 0.50, 0.05, 5, "model-v1",
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void lists_policies_newest_first_as_views() {
        when(policies.findAll()).thenReturn(java.util.List.of(policy("b", true), policy("a", false)));
        assertThat(service().listPolicies()).extracting(PolicyView::version).containsExactly("b", "a");
    }

    @Test
    void activating_an_unknown_version_is_a_bad_request() {
        doThrow(new IllegalArgumentException("no policy to activate with version nope"))
                .when(policies).activate("nope");
        assertThatThrownBy(() -> service().activatePolicy("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activating_a_known_version_flips_the_active_regime() {
        doNothing().when(policies).activate("b");
        when(policies.findByVersion("b")).thenReturn(Optional.of(policy("b", true)));

        PolicyView view = service().activatePolicy("b");

        verify(policies).activate("b");
        assertThat(view.version()).isEqualTo("b");
        assertThat(view.active()).isTrue();
    }

    @Test
    void applying_thresholds_with_no_active_policy_is_a_conflict() {
        when(policies.findActive()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().applyThresholds(new ThresholdsRequest(0.2, 0.5, 0.8, 0.5, 0.05)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void applying_thresholds_mints_and_activates_a_policy_carrying_the_active_burst_and_model() {
        when(policies.findActive()).thenReturn(Optional.of(policy("active", true)));
        ArgumentCaptor<Policy> saved = ArgumentCaptor.forClass(Policy.class);
        when(policies.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));
        when(policies.findByVersion(anyString()))
                .thenAnswer(inv -> Optional.of(withActive(saved.getValue())));

        PolicyView view = service().applyThresholds(new ThresholdsRequest(0.25, 0.55, 0.90, 0.40, 0.10));

        Policy minted = saved.getValue();
        assertThat(minted.warnThreshold()).isEqualTo(0.25);
        assertThat(minted.quarantineThreshold()).isEqualTo(0.55);
        assertThat(minted.blockThreshold()).isEqualTo(0.90);
        assertThat(minted.llmThreshold()).isEqualTo(0.40);
        assertThat(minted.routingBandWidth()).isEqualTo(0.10);
        // Carried from the active policy, not the request.
        assertThat(minted.burstThreshold()).isEqualTo(5);
        assertThat(minted.modelVersion()).isEqualTo("model-v1");
        assertThat(minted.active()).isFalse();
        verify(policies).activate(minted.version());
        assertThat(view.active()).isTrue();
    }

    @Test
    void rejects_a_non_monotonic_threshold_ladder() {
        when(policies.findActive()).thenReturn(Optional.of(policy("active", true)));
        // quarantine < warn violates the ladder; the Policy constructor throws.
        assertThatThrownBy(() -> service().applyThresholds(new ThresholdsRequest(0.7, 0.3, 0.9, 0.5, 0.05)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reads_and_updates_the_live_budget_caps() {
        ControlsService service = service();
        assertThat(service.budget()).isEqualTo(new BudgetView(true, 1.00, 10.00));

        BudgetView updated = service.updateBudget(new BudgetCapsRequest(0.25, 4.00));

        assertThat(updated).isEqualTo(new BudgetView(true, 0.25, 4.00));
        assertThat(service.budget().dailyCapUsd()).isEqualTo(0.25);
    }

    private static Policy withActive(Policy p) {
        return new Policy(p.version(), true, p.warnThreshold(), p.quarantineThreshold(),
                p.blockThreshold(), p.llmThreshold(), p.routingBandWidth(), p.burstThreshold(),
                p.modelVersion(), p.createdAt());
    }
}
