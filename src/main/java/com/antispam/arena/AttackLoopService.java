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
 * real adversarial search: the attacker perturbs real seeds (story 08.01), scores each variant against
 * a <em>fixed</em> defender, sees which variants beat it, and has the next generation target those
 * gaps — for a bounded number of generations under a hard spend ceiling ("even my red-team has a
 * budget").
 *
 * <p><b>Two-track (story 08.02b).</b> A run stresses both dimensions of the defender at once: Track A
 * ({@link Track#SPAM}) mutates abuse seeds and measures bypass — recall pressure — while Track B
 * ({@link Track#LEGIT}) mutates legit mail and measures false positives — precision pressure, the
 * good mail the defender starts blocking under attack. The two are tallied and reported separately
 * ({@code actualBypassRate} vs {@code precisionFpRate}); a variant's track is its preserved ground
 * truth, so the gap-targeting and metrics never confuse the two. Without Track B, hardening recall
 * could silently wreck precision.
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
    private final AdversarialEmailRepository variants;
    private final BypassMeasurementService measurement;
    private final ArenaProperties properties;

    @Autowired
    public AttackLoopService(MutationService mutations, PolicyScorer scorer, PolicyRepository policies,
            EmailRepository emails, AdversarialRunRepository runs, AdversarialEmailRepository variants,
            BypassMeasurementService measurement, ArenaProperties properties) {
        this.mutations = mutations;
        this.scorer = scorer;
        this.policies = policies;
        this.emails = emails;
        this.runs = runs;
        this.variants = variants;
        this.measurement = measurement;
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
            // The loop has terminated and recorded the current defender's bypass rate; now measure the
            // run against the fixed baseline and feed its bypassing variants into the retrain corpus
            // (story 08.04). This is the durable, demoable outcome of a campaign, so it is part of
            // running one — not a separate call a caller must remember to make.
            return measure(done);
        } finally {
            // Never leave a run dangling: an unexpected failure (e.g. attacker outage) finalizes it.
            if (!finalized) {
                finish(run, tally, budget, RunStatus.FAILED);
            }
        }
    }

    /**
     * Measures the terminated run against the baseline and feeds the corpus (story 08.04). A measurement
     * failure must not undo a campaign that already completed and recorded its result, so it is logged
     * and the un-baselined run is returned rather than propagated.
     */
    private AdversarialRun measure(AdversarialRun done) {
        try {
            return measurement.measure(done);
        } catch (RuntimeException e) {
            log.warn("run {} completed but bypass measurement failed: {}", done.id(), e.getMessage(), e);
            return done;
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
            // Target the gaps: the next generation attacks only what beat the defender (a bypass on
            // Track A, a false positive on Track B). No gap on either track → converged, stop.
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
            // Score under the fixed defender, then stamp the verdict on the variant so a bypass (Track
            // A) or a wrongly-blocked good mail (Track B) is durable and queryable. Whether that counts
            // as the attacker winning depends on the variant's track: abuse wins by delivery, legit by
            // being withheld.
            boolean delivered = defenderDelivers(variant, defender);
            variants.recordDefenderOutcome(variant.id(), delivered);
            Track track = variant.track();
            boolean beatDefender = track.attackerWon(delivered);
            results.add(new ScoredVariant(variant, beatDefender));
            tally.record(track, beatDefender);
        }
        return new GenerationOutcome(results, false);
    }

    private AdversarialEmail mint(AttackTarget target, UUID runId, int generation) {
        if (target.parentVariant() == null) {
            return mutations.mutateSeed(
                    target.seedEmailId(), target.strategy(), target.track(), runId, generation);
        }
        return mutations.mutateVariant(target.parentVariant(), target.strategy(), runId, generation);
    }

    /**
     * Whether the fixed defender would deliver this variant to the inbox. Scored read-only under the
     * captured policy, so the experiment cannot write live state; delivery is the verdict
     * {@link com.antispam.decision.Decision#delivers() delivering} it (allow or warn). What delivery
     * <em>means</em> for the attacker depends on the variant's track — a bypass for abuse, the absence
     * of a false positive for legit mail — but that interpretation lives in {@link Track}, not here.
     */
    private boolean defenderDelivers(AdversarialEmail variant, Policy defender) {
        Email email = emails.findById(variant.variantEmailId()).orElseThrow(() ->
                new IllegalStateException("variant email vanished: " + variant.variantEmailId()));
        ScoredDecision scored = ExperimentContext.callReadOnly(() -> scorer.score(email, defender));
        return scored.decision().delivers();
    }

    private AdversarialRun finish(AdversarialRun run, Tally tally, RunBudget budget, RunStatus status) {
        AdversarialRun done = runs.complete(run.id(), tally.recallBypassRate(), tally.precisionFpRate(),
                budget.spentUsd(), tally.generationsRun, status);
        log.info("finished adversarial run {} status={} generations={} bypassRate={} fpRate={} spent={}",
                done.id(), done.status().dbValue(), done.generationsRun(),
                done.actualBypassRate(), done.precisionFpRate(), done.spentUsd());
        return done;
    }

    /**
     * Generation one (story 08.02b): every seed crossed with every configured strategy that its track
     * can apply — abuse seeds on Track A (recall) and legit seeds on Track B (precision). A strategy
     * that would not preserve a legit seed's ground truth (reframe, homoglyph) is simply not applied on
     * Track B, so a ham variant always stays ham.
     */
    private static List<AttackTarget> seedTargets(AttackRunConfig config) {
        List<AttackTarget> targets = new ArrayList<>();
        addSeedTargets(targets, config.spamSeedEmailIds(), config.strategies(), Track.SPAM);
        addSeedTargets(targets, config.legitSeedEmailIds(), config.strategies(), Track.LEGIT);
        return targets;
    }

    private static void addSeedTargets(List<AttackTarget> targets, List<UUID> seeds,
            List<MutationStrategy> strategies, Track track) {
        for (UUID seed : seeds) {
            for (MutationStrategy strategy : strategies) {
                if (track.applicableStrategies().contains(strategy)) {
                    targets.add(AttackTarget.ofSeed(seed, strategy, track));
                }
            }
        }
    }

    /**
     * The gap-targeting step (AC 2): given a generation's results, the next generation's targets are
     * the variants that beat the defender, each re-attacked with the very strategy that worked, on the
     * same track — so attacker effort concentrates on previously-successful strategies/variants rather
     * than starting over, for recall and precision alike. A variant the defender handled correctly
     * contributes no target, so a generation that beats the defender nowhere leaves no gap and the loop
     * converges.
     */
    static List<AttackTarget> targetGaps(List<ScoredVariant> priorGeneration) {
        return priorGeneration.stream()
                .filter(ScoredVariant::beatDefender)
                .map(scored -> new AttackTarget(scored.variant().seedEmailId(), scored.variant(),
                        scored.variant().strategy(), scored.variant().track()))
                .toList();
    }

    /** The per-variant verdicts of one generation, and whether the budget ran out before it finished. */
    private record GenerationOutcome(List<ScoredVariant> results, boolean budgetExhausted) {
    }

    /**
     * Running per-track totals carried across generations and read at finalization (story 08.02b).
     * Track A and Track B are tallied separately so recall pressure (abuse bypassed) and precision
     * pressure (legit wrongly blocked) are reported as two independent rates (AC 3), each null when its
     * track did not run.
     */
    private static final class Tally {
        private int spamScored;
        private int spamBypassed;
        private int hamScored;
        private int hamBlocked;
        private int generationsRun;

        void record(Track track, boolean beatDefender) {
            if (track == Track.SPAM) {
                spamScored++;
                if (beatDefender) {
                    spamBypassed++;
                }
            } else {
                hamScored++;
                if (beatDefender) {
                    hamBlocked++;
                }
            }
        }

        /** Track A recall: abuse variants delivered / abuse variants scored, or null if none scored. */
        Double recallBypassRate() {
            return spamScored == 0 ? null : (double) spamBypassed / spamScored;
        }

        /** Track B precision: legit variants wrongly blocked / legit variants scored, or null if none. */
        Double precisionFpRate() {
            return hamScored == 0 ? null : (double) hamBlocked / hamScored;
        }
    }
}
