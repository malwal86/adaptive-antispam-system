package com.antispam.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.analyze.AnalyzeService;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The thunderclap runner's orchestration (story 12.05), unit-level: it validates the scenario name,
 * designates a shadow policy, and feeds every scripted email through the live pipeline in order. The
 * dispatcher is synchronous here so the asynchronously-dispatched loop is observable inline, and the
 * step delay is zero so the test doesn't sleep; the real pipeline effects are pinned by the
 * integration test.
 */
@ExtendWith(MockitoExtension.class)
class ScenarioServiceTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-06-25T00:00:00Z"), ZoneOffset.UTC);
    private static final ScenarioProperties NO_PACING = new ScenarioProperties(Duration.ZERO, 42L);

    @Mock
    private AnalyzeService analyzeService;
    @Mock
    private PolicyRepository policies;

    /** A dispatcher that runs the loop inline, so the test sees its effects without threading. */
    private final ScenarioDispatcher inline = Runnable::run;

    private ScenarioService service() {
        return service(NO_PACING);
    }

    private ScenarioService service(ScenarioProperties properties) {
        return new ScenarioService(analyzeService, policies, inline, properties, FIXED);
    }

    private static Policy active(String version) {
        return new Policy(version, true, 0.30, 0.60, 0.85, 0.50, 0.05, 5, "model-v1",
                Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    void an_unknown_scenario_is_a_bad_request_and_injects_nothing() {
        assertThatThrownBy(() -> service().start("not_a_scenario", null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(analyzeService, never()).analyzeRaw(any(), anyString());
    }

    @Test
    void starting_injects_every_scripted_email_through_the_live_pipeline_in_beat_order() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.of(active("active")));

        ScenarioRun run = service().start(ThunderclapScript.NAME, 7L);

        // Every scripted email was decided through the same AnalyzeService every surface uses, tagged
        // with its beat's provenance, and the run summary matches the script it built.
        ArgumentCaptor<String> sources = ArgumentCaptor.forClass(String.class);
        verify(analyzeService, org.mockito.Mockito.times(run.steps()))
                .analyzeRaw(any(), sources.capture());
        List<ScenarioEmail> expected = ThunderclapScript.build(7L);
        List<String> expectedSources = expected.stream().map(ScenarioEmail::source).toList();
        assertThat(sources.getAllValues()).isEqualTo(expectedSources);
        assertThat(run.steps()).isEqualTo(expected.size());
        assertThat(run.seed()).isEqualTo(7L);
        assertThat(run.scenario()).isEqualTo(ThunderclapScript.NAME);
    }

    @Test
    void the_default_seed_is_used_when_the_request_omits_one() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.of(active("active")));

        ScenarioRun run = service(new ScenarioProperties(Duration.ZERO, 99L)).start(ThunderclapScript.NAME, null);

        assertThat(run.seed()).isEqualTo(99L);
    }

    @Test
    void it_mints_a_stricter_shadow_policy_and_marks_it_so_the_diff_lights_up() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.of(active("active")));
        ArgumentCaptor<Policy> minted = ArgumentCaptor.forClass(Policy.class);
        when(policies.save(minted.capture())).thenAnswer(inv -> inv.getArgument(0));

        ScenarioRun run = service().start(ThunderclapScript.NAME, 1L);

        Policy shadow = minted.getValue();
        // Stricter than the active regime (every cut-point shifted down), a valid non-decreasing ladder,
        // and saved inactive then designated shadow.
        assertThat(shadow.warnThreshold()).isLessThan(0.30);
        assertThat(shadow.quarantineThreshold()).isLessThan(0.60);
        assertThat(shadow.blockThreshold()).isLessThan(0.85);
        assertThat(shadow.warnThreshold()).isLessThanOrEqualTo(shadow.quarantineThreshold());
        assertThat(shadow.quarantineThreshold()).isLessThanOrEqualTo(shadow.blockThreshold());
        assertThat(shadow.active()).isFalse();
        verify(policies).markShadow(shadow.version());
        assertThat(run.shadowPolicyVersion()).isEqualTo(shadow.version());
    }

    @Test
    void an_operators_existing_shadow_policy_is_respected_not_overwritten() {
        when(policies.findShadow()).thenReturn(Optional.of(active("operator-shadow")));

        ScenarioRun run = service().start(ThunderclapScript.NAME, 1L);

        assertThat(run.shadowPolicyVersion()).isEqualTo("operator-shadow");
        verify(policies, never()).save(any());
        verify(policies, never()).markShadow(anyString());
    }

    @Test
    void with_no_active_policy_the_run_still_proceeds_without_a_shadow() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.empty());

        ScenarioRun run = service().start(ThunderclapScript.NAME, 1L);

        assertThat(run.shadowPolicyVersion()).isNull();
        verify(policies, never()).save(any());
        verify(analyzeService, org.mockito.Mockito.atLeastOnce()).analyzeRaw(any(), anyString());
    }

    @Test
    void a_failing_email_does_not_abort_the_remaining_injections() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.of(active("active")));
        // The first email blows up; the rest must still be injected.
        List<String> injected = new ArrayList<>();
        when(analyzeService.analyzeRaw(any(), anyString())).thenAnswer(inv -> {
            String source = inv.getArgument(1);
            if (injected.isEmpty()) {
                injected.add(source);
                throw new RuntimeException("model unavailable");
            }
            injected.add(source);
            return null;
        });

        ScenarioRun run = service().start(ThunderclapScript.NAME, 1L);

        assertThat(injected).hasSize(run.steps());
    }

    @Test
    void a_concurrent_start_is_a_conflict_while_one_is_running() {
        when(policies.findShadow()).thenReturn(Optional.empty());
        when(policies.findActive()).thenReturn(Optional.of(active("active")));
        // A dispatcher that re-enters start() mid-run proves the second caller is rejected.
        ScenarioService[] holder = new ScenarioService[1];
        ScenarioDispatcher reentrant = task -> {
            assertThatThrownBy(() -> holder[0].start(ThunderclapScript.NAME, 1L))
                    .isInstanceOf(IllegalStateException.class);
            task.run();
        };
        holder[0] = new ScenarioService(analyzeService, policies, reentrant, NO_PACING, FIXED);

        holder[0].start(ThunderclapScript.NAME, 1L);

        // After the run completes the guard is released, so a fresh start is allowed again.
        assertThat(holder[0].isRunning()).isFalse();
    }
}
