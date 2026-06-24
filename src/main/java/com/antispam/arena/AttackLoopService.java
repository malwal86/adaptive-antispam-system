package com.antispam.arena;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.decision.policy.ScoredDecision;
import com.antispam.experiment.ExperimentContext;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * The bounded, budgeted iterative attack loop (story 08.02, PRD §Subsystem 6). One {@link #run} is a
 * real adversarial search: the attacker perturbs real seed spam (story 08.01), scores each variant
 * against a <em>fixed</em> defender, sees which variants bypassed, and has the next generation target
 * those gaps — for a bounded number of generations under a hard spend ceiling ("even my red-team has
 * a budget").
 *
 * <p>Three invariants make this honest rather than runaway:
 *
 * <ul>
 *   <li><b>Bounded by construction (AC 3).</b> The loop runs at most {@link AttackRunConfig#generationCap}
 *       generations (1–5) and stops the moment the {@link RunBudget hard budget} cannot afford the next
 *       attacker call — it cannot run unbounded. It also stops early when a generation produces nothing
 *       that bypassed, since there is then no gap left to attack.</li>
 *   <li><b>The defender is fixed for the duration (AC 4).</b> The active policy is captured once at
 *       start and every generation is scored under exactly that {@link Policy}; a policy activated
 *       mid-run is ignored. Adaptation happens only <em>between</em> runs via retrain (Epic 10).</li>
 *   <li><b>Experiment-scoped (story 09.03).</b> Variants are scored read-only — a stray live-state
 *       write would be blocked — so a run reads live reputation and policies but never enforces a
 *       decision or accrues reputation.</li>
 * </ul>
 *
 * <p>Whatever stops the loop, the run is finalized with its partial results recorded (AC 5): the
 * generations completed, the spend consumed, and the achieved bypass rate. Even an infrastructure
 * failure (the attacker model going unreachable mid-run) finalizes the run {@link RunStatus#FAILED}
 * rather than leaving it dangling in {@code running}.
 */
@Service
public class AttackLoopService {

    private static final Logger log = LoggerFactory.getLogger(AttackLoopService.class);

    private final MutationService mutations;
    private final PolicyScorer scorer;
    private final PolicyRepository policies;
    private final EmailRepository emails;
    private final AdversarialRunRepository runs;
    private final ArenaProperties properties;

    @Autowired
    public AttackLoopService(MutationService mutations, PolicyScorer scorer, PolicyRepository policies,
            EmailRepository emails, AdversarialRunRepository runs, ArenaProperties properties) {
        this.mutations = mutations;
        this.scorer = scorer;
        this.policies = policies;
        this.emails = emails;
        this.runs = runs;
        this.properties = properties;
    }

    /**
     * Runs the bounded loop to termination and returns the finalized run.
     *
     * @throws MutationException            if there is no active policy to defend the run
     * @throws AttackerUnavailableException if the attacker model is unreachable; the run is finalized
     *                                      {@link RunStatus#FAILED} with its partial results before the
     *                                      exception propagates
     */
    public AdversarialRun run(AttackRunConfig config) {
        Policy defender = policies.findActive().orElseThrow(() ->
                new MutationException("no active policy to defend the arena run"));
        AdversarialRun run = runs.start(properties.attackerModel(), defender.modelVersion(),
                defender.version(), config.targetBypassRate(), config.generationCap(), config.budgetUsd());
        log.info("started adversarial run {} attacker={} defender={} policy={} cap={} budget={}",
                run.id(), run.attackerModel(), run.defenderModel(), run.defenderPolicyVersion(),
                run.generationCap(), run.budgetUsd());

        RunBudget budget = new RunBudget(config.budgetUsd());
        Tally tally = new Tally();
        boolean finalized = false;
        try {
            AdversarialRun done = loop(run, config, defender, budget, tally);
            finalized = true;
            return done;
        } finally {
            // Never leave a run dangling: an unexpected failure (e.g. attacker outage) finalizes it.
            if (!finalized) {
                finish(run, tally, budget, RunStatus.FAILED);
            }
        }
    }

    private AdversarialRun loop(AdversarialRun run, AttackRunConfig config, Policy defender,
            RunBudget budget, Tally tally) {
        List<AttackTarget> targets = seedTargets(config);
        RunStatus status = RunStatus.COMPLETED;
        for (int generation = 1; generation <= config.generationCap(); generation++) {
            GenerationOutcome outcome = runGeneration(run, defender, targets, generation, budget, tally);
            tally.generationsRun = generation;
            if (outcome.budgetExhausted()) {
                status = RunStatus.BUDGET_EXHAUSTED;
                break;
            }
            if (generation == config.generationCap()) {
                break;
            }
            // Target the gaps: the next generation attacks only what bypassed. No gap → converged, stop.
            targets = targetGaps(outcome.results());
            if (targets.isEmpty()) {
                break;
            }
        }
        return finish(run, tally, budget, status);
    }

    /**
     * Mints and scores every target of one generation, stopping the moment the budget cannot afford
     * the next attacker call. Returns the per-variant results and whether the budget ran out mid-way.
     */
    private GenerationOutcome runGeneration(AdversarialRun run, Policy defender,
            List<AttackTarget> targets, int generation, RunBudget budget, Tally tally) {
        BigDecimal cost = properties.costPerMutationUsd();
        List<ScoredVariant> results = new ArrayList<>();
        for (AttackTarget target : targets) {
            if (!budget.canAfford(cost)) {
                log.info("run {} budget exhausted in generation {} (spent {})",
                        run.id(), generation, budget.spentUsd());
                return new GenerationOutcome(results, true);
            }
            // Charge before the call, like the LLM budget reserves before its call: the spend is the
            // attacker invocation, which happens whether or not the perturbation turns out usable.
            budget.charge(cost);
            AdversarialEmail variant;
            try {
                variant = mint(target, run.id(), generation);
            } catch (MutationException degenerate) {
                log.warn("run {} gen {}: skipping degenerate mutation: {}",
                        run.id(), generation, degenerate.getMessage());
                continue;
            }
            boolean bypassed = bypasses(variant, defender);
            results.add(new ScoredVariant(variant, bypassed));
            tally.scored++;
            if (bypassed) {
                tally.bypassed++;
            }
        }
        return new GenerationOutcome(results, false);
    }

    private AdversarialEmail mint(AttackTarget target, UUID runId, int generation) {
        if (target.parentVariant() == null) {
            return mutations.mutateInRun(target.seedEmailId(), target.strategy(), runId, generation);
        }
        return mutations.mutateVariant(target.parentVariant(), target.strategy(), runId, generation);
    }

    /**
     * Whether the fixed defender would let this spam/phish variant through. Scored read-only under the
     * captured policy, so the experiment cannot write live state; a variant bypasses when the verdict
     * {@link com.antispam.decision.Decision#delivers() delivers} it to the inbox (allow or warn).
     */
    private boolean bypasses(AdversarialEmail variant, Policy defender) {
        Email email = emails.findById(variant.variantEmailId()).orElseThrow(() ->
                new IllegalStateException("variant email vanished: " + variant.variantEmailId()));
        ScoredDecision scored = ExperimentContext.callReadOnly(() -> scorer.score(email, defender));
        return scored.decision().delivers();
    }

    private AdversarialRun finish(AdversarialRun run, Tally tally, RunBudget budget, RunStatus status) {
        double actualBypassRate = tally.scored == 0 ? 0.0 : (double) tally.bypassed / tally.scored;
        AdversarialRun done = runs.complete(
                run.id(), actualBypassRate, budget.spentUsd(), tally.generationsRun, status);
        log.info("finished adversarial run {} status={} generations={} bypassRate={} spent={}",
                done.id(), done.status().dbValue(), done.generationsRun(),
                done.actualBypassRate(), done.spentUsd());
        return done;
    }

    /** Generation one: every seed crossed with every configured strategy. */
    private static List<AttackTarget> seedTargets(AttackRunConfig config) {
        List<AttackTarget> targets = new ArrayList<>();
        for (UUID seed : config.seedEmailIds()) {
            for (MutationStrategy strategy : config.strategies()) {
                targets.add(AttackTarget.ofSeed(seed, strategy));
            }
        }
        return targets;
    }

    /**
     * The gap-targeting step (AC 2): given a generation's results, the next generation's targets are
     * the variants that bypassed, each re-attacked with the very strategy that bypassed — so attacker
     * effort concentrates on previously-successful strategies/variants rather than starting over. A
     * variant the defender caught contributes no target, so a generation that catches everything
     * leaves no gap and the loop converges.
     */
    static List<AttackTarget> targetGaps(List<ScoredVariant> priorGeneration) {
        return priorGeneration.stream()
                .filter(ScoredVariant::bypassed)
                .map(scored -> new AttackTarget(
                        scored.variant().seedEmailId(), scored.variant(), scored.variant().strategy()))
                .toList();
    }

    /** The per-variant verdicts of one generation, and whether the budget ran out before it finished. */
    private record GenerationOutcome(List<ScoredVariant> results, boolean budgetExhausted) {
    }

    /** Running totals carried across generations and read at finalization. */
    private static final class Tally {
        private int scored;
        private int bypassed;
        private int generationsRun;
    }
}
