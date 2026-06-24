package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.ingest.ParsedEmail;
import com.antispam.seed.GroundTruthLabel;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The bounded loop's control flow (story 08.02), pinned with mocks so it needs no provider or
 * database. It proves the run is recorded with its attacker/defender/target (AC 1), the defender is
 * captured once and fixed for the run (AC 4), later generations target the variants that bypassed
 * (AC 2), the loop stops at the generation cap and on budget exhaustion (AC 3, AC 5), and a
 * budget-exhausted run still records its partial results (AC 5).
 */
@ExtendWith(MockitoExtension.class)
class AttackLoopServiceTest {

    @Mock
    private MutationService mutations;
    @Mock
    private PolicyScorer scorer;
    @Mock
    private PolicyRepository policies;
    @Mock
    private EmailRepository emails;
    @Mock
    private AdversarialRunRepository runs;

    private static final UUID SEED = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Policy DEFENDER = policy("pol-active", "model-7");

    private AttackLoopService service(BigDecimal budget, BigDecimal costPerMutation) {
        ArenaProperties props = new ArenaProperties(true, "attacker-x", 3, budget, costPerMutation);
        return new AttackLoopService(mutations, scorer, policies, emails, runs, props);
    }

    @Test
    void records_the_run_with_attacker_defender_and_target() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        // Defender catches the only variant → the loop converges after generation one.
        stubMint();
        stubScore(Decision.BLOCK);
        stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01"))
                .run(config(0.4, 3, new BigDecimal("1.00")));

        verify(runs).start("attacker-x", "model-7", "pol-active", 0.4, 3, new BigDecimal("1.00"));
    }

    @Test
    void captures_the_defender_once_and_scores_every_generation_under_it() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        stubMint();
        stubScore(Decision.ALLOW); // always bypass → loop runs to the cap, scoring every generation
        stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(config(0.4, 3, new BigDecimal("1.00")));

        // The active policy is read exactly once (AC 4): a policy activated mid-run cannot change the
        // defender, because the captured Policy — not a re-read — is what every score uses.
        verify(policies, times(1)).findActive();
        ArgumentCaptor<Policy> scoredUnder = ArgumentCaptor.forClass(Policy.class);
        verify(scorer, atLeastOnce()).score(any(), scoredUnder.capture());
        assertThat(scoredUnder.getAllValues()).allMatch(p -> p.version().equals("pol-active"));
    }

    @Test
    void stops_at_the_generation_cap_even_when_every_variant_keeps_bypassing() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        stubMint();
        stubScore(Decision.ALLOW); // nothing is ever caught, so only the cap can stop the loop
        ArgumentCaptor<RunStatus> status = stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(config(0.9, 3, new BigDecimal("1.00")));

        // One seed × one strategy: generation 1 mutates the seed; generations 2 and 3 mutate the prior
        // variant (AC 2). Exactly the cap of 3 generations, then a clean stop (AC 3).
        verify(mutations, times(1)).mutateInRun(eq(SEED), any(), eq(RUN_ID), eq(1));
        verify(mutations, times(2)).mutateVariant(any(), any(), eq(RUN_ID), anyInt());
        verify(runs).complete(eq(RUN_ID), eq(1.0), any(), eq(3), eq(RunStatus.COMPLETED));
        assertThat(status.getValue()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void generation_two_builds_on_the_variant_that_bypassed() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        AdversarialEmail gen1 = variant(1);
        when(mutations.mutateInRun(eq(SEED), any(), eq(RUN_ID), eq(1))).thenReturn(gen1);
        when(mutations.mutateVariant(any(), any(), eq(RUN_ID), anyInt())).thenReturn(variant(2));
        stubEmailForAnyVariant();
        stubScore(Decision.WARN); // warn still delivers → bypass, so gen 1's variant becomes gen 2's parent
        stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(config(0.9, 2, new BigDecimal("1.00")));

        // The gap-targeting step fed exactly the bypassing gen-1 variant back in as gen 2's parent.
        verify(mutations).mutateVariant(eq(gen1), any(), eq(RUN_ID), eq(2));
    }

    @Test
    void stops_and_records_partial_results_when_the_budget_is_exhausted() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        stubMint();
        stubScore(Decision.ALLOW);
        ArgumentCaptor<RunStatus> status = stubStartAndComplete();

        // Budget affords exactly two attacker calls (0.20 / 0.10). Three seeds in generation one means
        // the third mutation cannot be afforded → the loop stops mid-generation-one, before the cap.
        AttackRunConfig threeSeeds = new AttackRunConfig(
                List.of(SEED, UUID.randomUUID(), UUID.randomUUID()),
                List.of(MutationStrategy.SYNONYM), 0.9, 3, new BigDecimal("0.20"));
        service(new BigDecimal("0.20"), new BigDecimal("0.10")).run(threeSeeds);

        // Hard stop: only the two affordable mutations were minted, and the run is finalized as
        // budget-exhausted with its partial spend and the one generation it reached (AC 5).
        verify(mutations, times(2)).mutateInRun(any(), any(), eq(RUN_ID), eq(1));
        verify(runs).complete(eq(RUN_ID), eq(1.0), eq(new BigDecimal("0.20")), eq(1),
                eq(RunStatus.BUDGET_EXHAUSTED));
        assertThat(status.getValue()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void finalizes_the_run_as_failed_when_the_attacker_is_unreachable_mid_run() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        when(mutations.mutateInRun(any(), any(), any(), any()))
                .thenThrow(new AttackerUnavailableException("attacker down", new RuntimeException()));
        ArgumentCaptor<RunStatus> status = stubStartAndComplete();

        try {
            service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(config(0.4, 3, new BigDecimal("1.00")));
        } catch (AttackerUnavailableException expected) {
            // propagates to the caller (→ 503); the run must still be finalized, not left dangling.
        }

        assertThat(status.getValue()).isEqualTo(RunStatus.FAILED);
        verify(runs).complete(eq(RUN_ID), any(Double.class), any(), anyInt(), eq(RunStatus.FAILED));
    }

    @Test
    void rejects_a_run_with_no_active_policy_to_defend() {
        when(policies.findActive()).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        service(new BigDecimal("1.00"), new BigDecimal("0.01"))
                                .run(config(0.4, 3, new BigDecimal("1.00"))))
                .isInstanceOf(MutationException.class);
        verify(runs, never()).start(any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(),
                anyInt(), any());
    }

    // --- stubs / builders -------------------------------------------------------------------------

    private void stubMint() {
        when(mutations.mutateInRun(any(), any(), any(), any()))
                .thenAnswer(inv -> variant((Integer) inv.getArgument(3)));
        org.mockito.Mockito.lenient().when(mutations.mutateVariant(any(), any(), any(), anyInt()))
                .thenAnswer(inv -> variant(inv.getArgument(3)));
        stubEmailForAnyVariant();
    }

    private void stubScore(Decision decision) {
        when(scorer.score(any(), any())).thenReturn(new ScoredDecision(
                decision, List.of(), RouteUsed.MODEL, List.of(), "pol-active", 0.5));
    }

    private void stubEmailForAnyVariant() {
        org.mockito.Mockito.lenient().when(emails.findById(any()))
                .thenAnswer(inv -> Optional.of(email(inv.getArgument(0))));
    }

    /** Stubs start() to return a run with RUN_ID and complete() to echo its status; captures the status. */
    private ArgumentCaptor<RunStatus> stubStartAndComplete() {
        when(runs.start(any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(), anyInt(), any()))
                .thenReturn(run(RunStatus.RUNNING, null));
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        when(runs.complete(eq(RUN_ID), org.mockito.ArgumentMatchers.anyDouble(), any(), anyInt(),
                status.capture())).thenAnswer(inv -> run(inv.getArgument(4), inv.getArgument(1)));
        return status;
    }

    private static AttackRunConfig config(double target, int cap, BigDecimal budget) {
        return new AttackRunConfig(List.of(SEED), List.of(MutationStrategy.SYNONYM), target, cap, budget);
    }

    private static AdversarialEmail variant(int generation) {
        return new AdversarialEmail(UUID.randomUUID(), UUID.randomUUID(), SEED, null,
                MutationStrategy.SYNONYM, GroundTruthLabel.SPAM, "attacker-x", RUN_ID, generation, Instant.EPOCH);
    }

    private static AdversarialRun run(RunStatus status, Double actualBypassRate) {
        return new AdversarialRun(RUN_ID, "attacker-x", "model-7", "pol-active", 0.4, actualBypassRate,
                3, new BigDecimal("1.00"), new BigDecimal("0.00"), 0, status, Instant.EPOCH, null);
    }

    private static Email email(UUID id) {
        return new Email(id, new byte[32], "Subject: x\n\nbody".getBytes(),
                new ParsedEmail("s@evil.test", "evil.test", null, "x", null, null), "adversarial", Instant.EPOCH);
    }

    private static Policy policy(String version, String modelVersion) {
        return new Policy(version, true, 0.5, 0.7, 0.9, 0.5, 0.05, 5, modelVersion, Instant.EPOCH);
    }
}
