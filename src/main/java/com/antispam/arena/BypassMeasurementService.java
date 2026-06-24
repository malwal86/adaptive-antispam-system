package com.antispam.arena;

import com.antispam.decision.policy.Policy;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.decision.policy.PolicyScorer;
import com.antispam.experiment.ExperimentContext;
import com.antispam.ingest.EmailRepository;
import com.antispam.retrain.RetrainLabel;
import com.antispam.retrain.RetrainLabelRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Bypass-rate measurement against a fixed baseline, and the corpus feedback that closes the living loop
 * (story 08.04, PRD §Subsystem 6). The bounded loop (story 08.02) already records what the
 * <em>current</em> defender let through ({@code actualBypassRate}); this runs once after the loop
 * terminates and adds the two things that make the arena's headline claim measurable rather than hoped:
 *
 * <ul>
 *   <li><b>Danger missed by baseline (AC 2).</b> The run's variants are scored again under a
 *       <em>fixed</em> baseline defender — the genesis policy, or
 *       {@code antispam.arena.baseline-policy-version} when configured — and that baseline bypass rate
 *       is stamped on the run beside the current one. The same baseline across runs is the stable anchor
 *       the cross-run trend ({@link #trend}) is read against.</li>
 *   <li><b>Corpus feedback (AC 3, and the precision-floor half of story 08.02b AC 4).</b> Every variant
 *       that beat the fixed defender — abuse it delivered (a bypass) and legit it wrongly blocked (a
 *       false positive) — is fed into the append-only retrain corpus, labeled with its preserved ground
 *       truth and full arena provenance, so the next model is harder and the precision floor is
 *       defended (Epic 10).</li>
 * </ul>
 *
 * <p>Scoring is read-only ({@link ExperimentContext#callReadOnly}), so re-scoring under the baseline
 * never enforces a decision or accrues reputation — the only durable writes are the baseline rate on the
 * run and the labels on the corpus, both deliberate arena outputs. Those labels carry {@code source =}
 * {@value #ARENA_SOURCE}, the discriminator Epic 11 uses to feed arena ground truth into <em>training</em>
 * while keeping it out of the golden judging set (AC 5).
 */
@Service
public class BypassMeasurementService {

    /** Provenance token marking a label as arena-minted, so Epic 11 can keep it out of the golden set. */
    static final String ARENA_SOURCE = "arena";

    private static final Logger log = LoggerFactory.getLogger(BypassMeasurementService.class);

    private final PolicyScorer scorer;
    private final PolicyRepository policies;
    private final EmailRepository emails;
    private final AdversarialRunRepository runs;
    private final AdversarialEmailRepository variants;
    private final RetrainLabelRepository retrainLabels;
    private final ArenaProperties properties;
    private final ObjectMapper objectMapper;

    @Autowired
    public BypassMeasurementService(PolicyScorer scorer, PolicyRepository policies, EmailRepository emails,
            AdversarialRunRepository runs, AdversarialEmailRepository variants,
            RetrainLabelRepository retrainLabels, ArenaProperties properties, ObjectMapper objectMapper) {
        this.scorer = scorer;
        this.policies = policies;
        this.emails = emails;
        this.runs = runs;
        this.variants = variants;
        this.retrainLabels = retrainLabels;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * Measures a terminated run against the fixed baseline and feeds its bypassing variants into the
     * retrain corpus. Returns the run with its baseline comparison stamped on (or the run unchanged if no
     * baseline policy was resolvable).
     */
    public AdversarialRun measure(AdversarialRun run) {
        AdversarialRun measured = measureBaseline(run);
        feedCorpus(measured);
        return measured;
    }

    /**
     * The cross-run bypass-rate trend over the most recent {@code limit} terminal runs (AC 4): the same
     * comparison each run already makes against the fixed baseline, lined up over time so "bypass rate
     * drops as the defender retrains" is a reported series.
     */
    public BypassTrend trend(int limit) {
        List<BypassTrendPoint> points = runs.findRecentTerminal(limit).stream()
                .map(r -> new BypassTrendPoint(r.id(), r.createdAt(), r.actualBypassRate(),
                        r.baselineBypassRate(), r.precisionFpRate()))
                .toList();
        Double first = firstRate(points);
        Double latest = latestRate(points);
        boolean improved = first != null && latest != null && latest < first;
        return new BypassTrend(points, first, latest, improved);
    }

    /**
     * Scores the run's abuse variants under the fixed baseline and records the baseline bypass rate.
     * Re-scoring is read-only; the variants were persisted by the loop, so this needs no attacker spend.
     */
    private AdversarialRun measureBaseline(AdversarialRun run) {
        Optional<Policy> resolved = resolveBaseline();
        if (resolved.isEmpty()) {
            log.warn("run {}: no baseline policy resolvable; skipping the baseline comparison", run.id());
            return run;
        }
        Policy baseline = resolved.get();
        int abuseScored = 0;
        int abuseBypassed = 0;
        for (AdversarialEmail variant : variants.findByRun(run.id())) {
            if (variant.track() != Track.SPAM) {
                continue; // baseline comparison is the recall (Track A) number — "danger missed".
            }
            abuseScored++;
            if (VariantScorer.delivers(emails, scorer, variant, baseline)) {
                abuseBypassed++;
            }
        }
        Double baselineBypassRate = abuseScored == 0 ? null : (double) abuseBypassed / abuseScored;
        AdversarialRun measured = runs.recordBaseline(run.id(), baseline.version(), baselineBypassRate);
        log.info("run {} baseline={} baselineBypassRate={} vs currentBypassRate={}",
                run.id(), baseline.version(), baselineBypassRate, run.actualBypassRate());
        return measured;
    }

    /** The configured baseline if it exists, otherwise the genesis policy (the stable default anchor). */
    private Optional<Policy> resolveBaseline() {
        String configured = properties.baselinePolicyVersion();
        if (configured != null && !configured.isBlank()) {
            Optional<Policy> byVersion = policies.findByVersion(configured);
            if (byVersion.isPresent()) {
                return byVersion;
            }
            log.warn("configured arena baseline policy {} not found; falling back to the genesis policy",
                    configured);
        }
        return policies.findOldest();
    }

    /**
     * Feeds every variant that beat the fixed defender into the retrain corpus (AC 3): abuse it delivered
     * (a bypass) labeled with its abuse class, and legit it wrongly blocked (a false positive) labeled
     * ham — each weighted and carrying arena provenance. A run that the defender handled perfectly writes
     * nothing.
     */
    private void feedCorpus(AdversarialRun run) {
        List<RetrainLabel> labels = new ArrayList<>();
        for (AdversarialEmail bypass : variants.findBypassingAbuse(run.id())) {
            labels.add(label(bypass, run, CorpusOutcome.BYPASS));
        }
        for (AdversarialEmail falsePositive : variants.findWronglyBlockedHam(run.id())) {
            labels.add(label(falsePositive, run, CorpusOutcome.FALSE_POSITIVE));
        }
        if (labels.isEmpty()) {
            log.info("run {}: defender beaten nowhere; no variants fed to the retrain corpus", run.id());
            return;
        }
        retrainLabels.saveAll(labels);
        log.info("run {}: fed {} variants into the retrain corpus (source={})",
                run.id(), labels.size(), ARENA_SOURCE);
    }

    private RetrainLabel label(AdversarialEmail variant, AdversarialRun run, CorpusOutcome outcome) {
        return new RetrainLabel(UUID.randomUUID(), variant.variantEmailId(), variant.label(),
                properties.corpusLabelWeight(), ARENA_SOURCE, provenance(variant, run, outcome));
    }

    private String provenance(AdversarialEmail variant, AdversarialRun run, CorpusOutcome outcome) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("runId", run.id().toString());
        provenance.put("variantId", variant.id().toString());
        provenance.put("seedEmailId", variant.seedEmailId().toString());
        provenance.put("generation", variant.generation());
        provenance.put("track", variant.track().name());
        provenance.put("strategy", variant.strategy().dbValue());
        provenance.put("attackerModel", variant.attackerModel());
        provenance.put("outcome", outcome.token);
        provenance.put("targetBypassRate", run.targetBypassRate());
        provenance.put("actualBypassRate", run.actualBypassRate());
        try {
            return objectMapper.writeValueAsString(provenance);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize arena label provenance", e);
        }
    }

    private static Double firstRate(List<BypassTrendPoint> points) {
        return points.stream().map(BypassTrendPoint::bypassRate).filter(r -> r != null).findFirst()
                .orElse(null);
    }

    private static Double latestRate(List<BypassTrendPoint> points) {
        Double latest = null;
        for (BypassTrendPoint point : points) {
            if (point.bypassRate() != null) {
                latest = point.bypassRate();
            }
        }
        return latest;
    }

    /** Why a variant entered the corpus — a Track A bypass or a Track B false positive. */
    private enum CorpusOutcome {
        BYPASS("bypass"),
        FALSE_POSITIVE("false_positive");

        private final String token;

        CorpusOutcome(String token) {
            this.token = token;
        }
    }
}
