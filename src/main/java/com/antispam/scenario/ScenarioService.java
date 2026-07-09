package com.antispam.scenario;

import com.antispam.analyze.AnalyzeService;
import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.decision.calibration.ProbabilityCalibrator;
import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Drives a scripted scenario (story 12.05): a single control action that injects a named scenario's
 * emails through the live pipeline so the console animates real backend decisions. The
 * {@link ScenarioCatalog} resolves the name to a {@link Scenario} builder, so which stories exist is
 * open-ended — the thunderclap's rise-then-collapse, a calm normal-morning triage, and any scenario
 * added later — while this runner's job (prep, pace, one-at-a-time) stays the same.
 *
 * <p>It composes existing machinery rather than reimplementing any of it: a {@link Scenario}
 * builds the emails, {@link AnalyzeService} runs each through the <em>same live pipeline</em> every
 * other surface uses (so each decision is a real backend signal and publishes onto the live SSE
 * stream), and a shadow policy is designated up front so the shadow comparison records during the
 * attack. The injection loop runs on a background thread via {@link ScenarioDispatcher}, paced so the
 * beats unfold visibly; {@code start} returns immediately with what it accepted.
 */
@Service
public class ScenarioService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioService.class);

    /**
     * How far below the active policy's cut-points the auto-designated shadow regime sits, so the
     * shadow quarantines borderline mail the active policy still allows — a visible disagreement
     * during the attack rather than a degenerate identical-policy no-diff.
     */
    private static final double SHADOW_DELTA = 0.10;

    private final ScenarioCatalog catalog;
    private final AnalyzeService analyzeService;
    private final PolicyRepository policies;
    private final ActiveCalibrator calibrator;
    private final ReputationService reputationService;
    private final ScenarioDispatcher dispatcher;
    private final ScenarioProperties properties;
    private final Clock clock;

    // One scenario at a time: overlapping runs would interleave two stories on one stream and scramble
    // the reputation curve. A second start while one is running is rejected (409), not queued.
    private final AtomicBoolean running = new AtomicBoolean(false);
    // Disambiguates two shadow policies minted within the same clock tick.
    private final AtomicLong shadowSequence = new AtomicLong(0);

    @Autowired
    public ScenarioService(
            ScenarioCatalog catalog,
            AnalyzeService analyzeService,
            PolicyRepository policies,
            ActiveCalibrator calibrator,
            ReputationService reputationService,
            ScenarioDispatcher dispatcher,
            ScenarioProperties properties,
            Clock clock) {
        this.catalog = catalog;
        this.analyzeService = analyzeService;
        this.policies = policies;
        this.calibrator = calibrator;
        this.reputationService = reputationService;
        this.dispatcher = dispatcher;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Starts {@code name} with the given seed (or the configured default when {@code seed} is null),
     * designating a shadow policy and dispatching the injection loop, then returns immediately.
     *
     * @throws IllegalArgumentException if {@code name} is not a known scenario (→ 400)
     * @throws IllegalStateException    if a scenario is already running (→ 409)
     */
    public ScenarioRun start(String name, Long seed) {
        Scenario scenario = catalog.find(name)
                .orElseThrow(() -> new IllegalArgumentException("unknown scenario: " + name));
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("a scenario is already running");
        }
        try {
            long resolvedSeed = seed != null ? seed : properties.defaultSeed();
            List<ScenarioEmail> script = scenario.build(resolvedSeed);
            ensureCalibrated();
            prewarm(scenario);
            String shadowVersion = ensureShadowPolicy();
            dispatcher.dispatch(() -> runToCompletion(script));
            return new ScenarioRun(scenario.name(), script.size(), resolvedSeed, shadowVersion);
        } catch (RuntimeException e) {
            // Prep failed before the loop was handed off — release the guard so the demo isn't wedged.
            running.set(false);
            throw e;
        }
    }

    /**
     * Ensures the fusion stage will actually run for this demo. Fusion refuses to combine a raw model
     * score with reputation until a calibration is installed (story 04.04), and nothing installs one at
     * startup — so on a fresh system every model-route email is a provisional ALLOW and the scenario
     * could never turn hostile nor plot a trust curve. If no calibration is present we install the
     * identity so the model's own probabilities are fused as-is; an operator's real calibration, if
     * any, is respected and left untouched (the same "prep the world, don't clobber it" stance as
     * {@link #ensureShadowPolicy()}).
     */
    private void ensureCalibrated() {
        if (!calibrator.isCalibrated()) {
            calibrator.install(ProbabilityCalibrator.identity());
            log.info("scenario: no calibration installed; installed identity so the fusion stage runs");
        }
    }

    /**
     * Seeds the scenario's declared {@link Scenario#prewarm() good senders} with authenticated good
     * reputation before any mail flows, so their benign first email is a confident, instant inbox
     * decision on the model route instead of being escalated to the LLM as a brand-new sender would be
     * (that escalation is exactly what made the everyday-inbox demo a column of "checking…" cards). A
     * single high-weight good signal drops the sender's uncertainty below the routing band. Scenarios
     * that want cold-start senders (the thunderclap) declare no warm-ups, so this is a no-op for them.
     */
    private void prewarm(Scenario scenario) {
        for (Scenario.SenderWarmup warmup : scenario.prewarm()) {
            reputationService.record(
                    warmup.senderKey(), ReputationSignal.GOOD, warmup.weight(),
                    "scenario-warmup", ReputationBucket.AUTHENTICATED);
            log.info("scenario: pre-warmed sender={} weight={}", warmup.senderKey(), warmup.weight());
        }
    }

    /**
     * Injects every scripted email through the live pipeline in order, pacing between them, then
     * releases the run guard. Package-private and synchronous: the dispatcher calls it on a background
     * thread in production, and the integration test calls it directly to assert the pipeline effects.
     */
    void runToCompletion(List<ScenarioEmail> script) {
        long delayMillis = properties.stepDelay().toMillis();
        try {
            for (ScenarioEmail email : script) {
                inject(email);
                if (delayMillis > 0 && !pause(delayMillis)) {
                    break; // interrupted (e.g. shutdown) — stop pacing rather than spin
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** Whether a scenario is currently injecting — for the start guard's tests and for diagnostics. */
    public boolean isRunning() {
        return running.get();
    }

    private void inject(ScenarioEmail email) {
        try {
            analyzeService.analyzeRaw(email.raw(), email.source());
        } catch (RuntimeException e) {
            // One email failing to decide must not abort the demo; the stream simply skips it.
            log.warn("scenario email ({}) failed to decide: {}", email.beat(), e.toString());
        }
    }

    /**
     * Ensures a shadow policy is in force so the shadow-diff beat lights up. Respects an operator's
     * existing shadow designation; otherwise mints a stricter variant of the active policy and marks
     * it shadow. Returns the shadow version, or null when there is no active policy to derive one from
     * (the diff beat simply won't appear — the rest of the scenario is unaffected).
     */
    private String ensureShadowPolicy() {
        Optional<Policy> existing = policies.findShadow();
        if (existing.isPresent()) {
            return existing.get().version();
        }
        Optional<Policy> active = policies.findActive();
        if (active.isEmpty()) {
            return null;
        }
        Policy base = active.get();
        String version = "thunderclap-shadow-" + clock.instant().toEpochMilli()
                + "-" + shadowSequence.incrementAndGet();
        // Shift the whole ladder down by SHADOW_DELTA, clamped to a valid non-decreasing [0,1] ladder,
        // so the shadow is uniformly stricter than the active regime.
        double warn = clampDown(base.warnThreshold());
        double quarantine = Math.max(warn, clampDown(base.quarantineThreshold()));
        double block = Math.max(quarantine, clampDown(base.blockThreshold()));
        Policy shadow = new Policy(version, false, warn, quarantine, block,
                base.llmThreshold(), base.routingBandWidth(), base.burstThreshold(),
                base.modelVersion(), clock.instant());
        policies.save(shadow);
        policies.markShadow(version);
        return version;
    }

    private static double clampDown(double threshold) {
        return Math.max(0.0, threshold - SHADOW_DELTA);
    }

    private static boolean pause(long millis) {
        try {
            Thread.sleep(millis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
