package com.antispam.arena;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
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
 * The bounded loop's control flow (story 08.02) and its two-track behavior (story 08.02b), pinned
 * with mocks so it needs no provider or database. It proves the run is recorded with its
 * attacker/defender/target (AC 1), the defender is captured once and fixed for the run (AC 4), later
 * generations target the variants that beat the defender (AC 2), the loop stops at the generation cap
 * and on budget exhaustion (AC 3, AC 5), a budget-exhausted run still records its partial results
 * (AC 5), and — two-track — that recall pressure (abuse bypassed) and precision pressure (legit
 * wrongly blocked) are measured and reported as separate rates, with the wrongly-blocked legit
 * variants stamped for the precision-floor corpus.
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
    @Mock
    private AdversarialEmailRepository variants;

    private static final UUID SEED = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Policy DEFENDER = policy("pol-active", "model-7");

    private AttackLoopService service(BigDecimal budget, BigDecimal costPerMutation) {
        ArenaProperties props = new ArenaProperties(true, "attacker-x", 3, budget, costPerMutation);
        return new AttackLoopService(mutations, scorer, policies, emails, runs, variants, props);
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

        // One spam seed × one strategy: generation 1 mutates the seed (Track A); generations 2 and 3
        // mutate the prior variant (AC 2). Exactly the cap of 3 generations, then a clean stop (AC 3).
        verify(mutations, times(1)).mutateSeed(eq(SEED), any(), eq(Track.SPAM), eq(RUN_ID), eq(1));
        verify(mutations, times(2)).mutateVariant(any(), any(), eq(RUN_ID), anyInt());
        // Spam-only run: a recall bypass rate, no precision rate (no Track B ran).
        verify(runs).complete(eq(RUN_ID), eq(1.0), isNull(), any(), eq(3), eq(RunStatus.COMPLETED));
        assertThat(status.getValue()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void generation_two_builds_on_the_variant_that_bypassed() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        AdversarialEmail gen1 = variant(1);
        when(mutations.mutateSeed(eq(SEED), any(), eq(Track.SPAM), eq(RUN_ID), eq(1))).thenReturn(gen1);
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

        // Budget affords exactly two attacker calls (0.20 / 0.10). Three spam seeds in generation one
        // means the third mutation cannot be afforded → the loop stops mid-generation-one, before the cap.
        AttackRunConfig threeSeeds = new AttackRunConfig(
                List.of(SEED, UUID.randomUUID(), UUID.randomUUID()), List.of(),
                List.of(MutationStrategy.SYNONYM), 0.9, 3, new BigDecimal("0.20"));
        service(new BigDecimal("0.20"), new BigDecimal("0.10")).run(threeSeeds);

        // Hard stop: only the two affordable mutations were minted, and the run is finalized as
        // budget-exhausted with its partial spend and the one generation it reached (AC 5).
        verify(mutations, times(2)).mutateSeed(any(), any(), eq(Track.SPAM), eq(RUN_ID), eq(1));
        verify(runs).complete(eq(RUN_ID), eq(1.0), isNull(), eq(new BigDecimal("0.20")), eq(1),
                eq(RunStatus.BUDGET_EXHAUSTED));
        assertThat(status.getValue()).isEqualTo(RunStatus.BUDGET_EXHAUSTED);
    }

    @Test
    void measures_recall_and_precision_pressure_as_separate_rates() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        // One abuse seed and one legit seed, one shared strategy, single generation: gen 1 mints one
        // Track A variant and one Track B variant. The defender delivers everything.
        AdversarialEmail abuse = labelled(GroundTruthLabel.SPAM);
        AdversarialEmail legit = labelled(GroundTruthLabel.HAM);
        when(mutations.mutateSeed(any(), any(), eq(Track.SPAM), any(), any())).thenReturn(abuse);
        when(mutations.mutateSeed(any(), any(), eq(Track.LEGIT), any(), any())).thenReturn(legit);
        stubEmailForAnyVariant();
        stubScore(Decision.ALLOW); // delivered: the abuse bypasses (recall hit), the legit is fine (no FP)
        stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(twoTrack(1));

        // Recall stressed (1/1 abuse bypassed), precision clean (0/1 legit blocked) — reported separately.
        verify(runs).complete(eq(RUN_ID), eq(1.0), eq(0.0), any(), eq(1), eq(RunStatus.COMPLETED));
        // Each variant's defender verdict is stamped (both delivered here).
        verify(variants).recordDefenderOutcome(abuse.id(), true);
        verify(variants).recordDefenderOutcome(legit.id(), true);
    }

    @Test
    void a_precision_fragile_defender_drives_track_b_false_positives_and_captures_the_blocked_ham() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        AdversarialEmail abuse = labelled(GroundTruthLabel.SPAM);
        AdversarialEmail legit = labelled(GroundTruthLabel.HAM);
        when(mutations.mutateSeed(any(), any(), eq(Track.SPAM), any(), any())).thenReturn(abuse);
        when(mutations.mutateSeed(any(), any(), eq(Track.LEGIT), any(), any())).thenReturn(legit);
        stubEmailForAnyVariant();
        stubScore(Decision.BLOCK); // fragile: blocks the good mail too → a Track B false positive
        stubStartAndComplete();

        service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(twoTrack(1));

        // The arena catches the regression a spam-only run would miss: precision FP rate is elevated
        // (1/1 legit wrongly blocked) while recall shows no bypass (0/1 abuse delivered).
        verify(runs).complete(eq(RUN_ID), eq(0.0), eq(1.0), any(), eq(1), eq(RunStatus.COMPLETED));
        // The wrongly-blocked legit variant is stamped not-delivered — captured for the precision-floor corpus.
        verify(variants).recordDefenderOutcome(legit.id(), false);
    }

    @Test
    void finalizes_the_run_as_failed_when_the_attacker_is_unreachable_mid_run() {
        when(policies.findActive()).thenReturn(Optional.of(DEFENDER));
        when(mutations.mutateSeed(any(), any(), any(), any(), any()))
                .thenThrow(new AttackerUnavailableException("attacker down", new RuntimeException()));
        ArgumentCaptor<RunStatus> status = stubStartAndComplete();

        try {
            service(new BigDecimal("1.00"), new BigDecimal("0.01")).run(config(0.4, 3, new BigDecimal("1.00")));
        } catch (AttackerUnavailableException expected) {
            // propagates to the caller (→ 503); the run must still be finalized, not left dangling.
        }

        assertThat(status.getValue()).isEqualTo(RunStatus.FAILED);
        verify(runs).complete(eq(RUN_ID), nullable(Double.class), nullable(Double.class), any(),
                anyInt(), eq(RunStatus.FAILED));
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
        when(mutations.mutateSeed(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> variant((Integer) inv.getArgument(4)));
        lenient().when(mutations.mutateVariant(any(), any(), any(), anyInt()))
                .thenAnswer(inv -> variant(inv.getArgument(3)));
        stubEmailForAnyVariant();
    }

    private void stubScore(Decision decision) {
        when(scorer.score(any(), any())).thenReturn(new ScoredDecision(
                decision, List.of(), RouteUsed.MODEL, List.of(), "pol-active", 0.5));
    }

    private void stubEmailForAnyVariant() {
        lenient().when(emails.findById(any())).thenAnswer(inv -> Optional.of(email(inv.getArgument(0))));
    }

    /** Stubs start() to return a run with RUN_ID and complete() to echo its result; captures the status. */
    private ArgumentCaptor<RunStatus> stubStartAndComplete() {
        when(runs.start(any(), any(), any(), org.mockito.ArgumentMatchers.anyDouble(), anyInt(), any()))
                .thenReturn(run(RunStatus.RUNNING, null, null));
        ArgumentCaptor<RunStatus> status = ArgumentCaptor.forClass(RunStatus.class);
        when(runs.complete(eq(RUN_ID), nullable(Double.class), nullable(Double.class), any(), anyInt(),
                status.capture())).thenAnswer(inv ->
                run(inv.getArgument(5), inv.getArgument(1), inv.getArgument(2)));
        return status;
    }

    /** A spam-only run: one abuse seed, one strategy. */
    private static AttackRunConfig config(double target, int cap, BigDecimal budget) {
        return new AttackRunConfig(List.of(SEED), List.of(), List.of(MutationStrategy.SYNONYM),
                target, cap, budget);
    }

    /** A two-track run: one abuse seed and one legit seed, one strategy applicable to both. */
    private static AttackRunConfig twoTrack(int cap) {
        return new AttackRunConfig(List.of(SEED), List.of(UUID.randomUUID()),
                List.of(MutationStrategy.SYNONYM), 0.5, cap, new BigDecimal("1.00"));
    }

    private static AdversarialEmail variant(int generation) {
        return new AdversarialEmail(UUID.randomUUID(), UUID.randomUUID(), SEED, null,
                MutationStrategy.SYNONYM, GroundTruthLabel.SPAM, "attacker-x", RUN_ID, generation,
                null, Instant.EPOCH);
    }

    private static AdversarialEmail labelled(GroundTruthLabel label) {
        return new AdversarialEmail(UUID.randomUUID(), UUID.randomUUID(), SEED, null,
                MutationStrategy.SYNONYM, label, "attacker-x", RUN_ID, 1, null, Instant.EPOCH);
    }

    private static AdversarialRun run(RunStatus status, Double actualBypassRate, Double precisionFpRate) {
        return new AdversarialRun(RUN_ID, "attacker-x", "model-7", "pol-active", 0.4, actualBypassRate,
                precisionFpRate, 3, new BigDecimal("1.00"), new BigDecimal("0.00"), 0, status,
                Instant.EPOCH, null);
    }

    private static Email email(UUID id) {
        return new Email(id, new byte[32], "Subject: x\n\nbody".getBytes(),
                new ParsedEmail("s@evil.test", "evil.test", null, "x", null, null), "adversarial", Instant.EPOCH);
    }

    private static Policy policy(String version, String modelVersion) {
        return new Policy(version, true, 0.5, 0.7, 0.9, 0.5, 0.05, 5, modelVersion, Instant.EPOCH);
    }
}
