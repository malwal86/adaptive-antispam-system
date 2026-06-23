package com.antispam.feedback.gate;

import com.antispam.event.SenderKey;
import com.antispam.feedback.FeedbackEvent;
import com.antispam.feedback.FeedbackEventRepository;
import com.antispam.feedback.Persona;
import com.antispam.feedback.PersonaRepository;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.reputation.AuthGate;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
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
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The weighting/corroboration gate (story 07.03): the <b>single</b> bridge from raw feedback to
 * live state. Feedback is an attack surface (PRD §Subsystem 7), so this is the only code path that
 * turns {@code feedback_events} into reputation events or retrain labels — no other component reads
 * feedback and writes either sink, which is what makes AC 3 ("no path lets raw feedback mutate state
 * directly") hold by construction.
 *
 * <p><b>What it does for one run.</b> It reads the run's events, drops the {@code IGNORE}s (they
 * assert nothing, {@link FeedbackSignal}), and for each remaining event resolves the sender and
 * accrual bucket of its email and weights it by {@code trust × confidence} ({@link
 * FeedbackWeighting}) — full trust for a good-faith persona, a down-weight for a malicious one. It
 * then groups the weighted feedback by {@link CorroborationKey} (sender × polarity × bucket) and
 * asks {@link CorroborationGate} whether each group clears the trust thresholds. Only trusted groups
 * move state: each emits <em>one</em> reputation event (the corroborated, capped weight) to the
 * reputation sink and <em>one</em> retrain label per contributing item to the label sink, both
 * carrying full provenance (AC 4/AC 5). A single report — or a clutch of down-weighted bombers —
 * fails the gate and changes nothing (AC 1).
 *
 * <p><b>Idempotent.</b> Re-gating a run must not double-count: each trusted group claims itself in
 * the {@link ProcessedMessageLedger} under {@value #GATE_GROUP} before emitting, in the same
 * transaction as the writes, so a redelivery / re-run is recognised and skipped (the same primitive
 * the spine's reputation accrual uses, story 02.03). The whole method is one transaction: the ledger
 * claims, the reputation appends, and the label batch commit or roll back together.
 */
@Service
public class FeedbackGateService {

    /** This gate's dedupe scope in the {@link ProcessedMessageLedger}; one claim per trusted group. */
    static final String GATE_GROUP = "feedback-gate";

    /** Provenance stamped on both sinks — these signals come from gated feedback, not the decision path. */
    static final String SIGNAL_SOURCE = "feedback";

    private static final Logger log = LoggerFactory.getLogger(FeedbackGateService.class);

    private final FeedbackEventRepository feedbackEvents;
    private final PersonaRepository personas;
    private final EmailRepository emails;
    private final ReputationService reputation;
    private final RetrainLabelRepository retrainLabels;
    private final ProcessedMessageLedger ledger;
    private final FeedbackGateMeter meter;
    private final FeedbackGateProperties props;
    private final ObjectMapper objectMapper;

    @Autowired
    public FeedbackGateService(
            FeedbackEventRepository feedbackEvents,
            PersonaRepository personas,
            EmailRepository emails,
            ReputationService reputation,
            RetrainLabelRepository retrainLabels,
            ProcessedMessageLedger ledger,
            FeedbackGateMeter meter,
            FeedbackGateProperties props,
            ObjectMapper objectMapper) {
        this.feedbackEvents = feedbackEvents;
        this.personas = personas;
        this.emails = emails;
        this.reputation = reputation;
        this.retrainLabels = retrainLabels;
        this.ledger = ledger;
        this.meter = meter;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /**
     * Gates the feedback of one run: weights it, corroborates it, and emits only the trusted
     * aggregate to both sinks. Idempotent per run.
     *
     * @param runId the simulation run to gate
     * @return a summary of what the gate considered and what reached each sink
     */
    @Transactional
    public GateOutcome gate(UUID runId) {
        List<FeedbackEvent> events = feedbackEvents.findByRunId(runId);
        Map<UUID, Persona> personasById = personas.findAll().stream()
                .collect(Collectors.toMap(Persona::id, Function.identity()));
        Map<UUID, Email> emailsById = loadEmails(events);

        List<WeightedFeedback> weighted = new ArrayList<>();
        for (FeedbackEvent event : events) {
            weigh(event, personasById, emailsById).ifPresent(weighted::add);
        }

        Map<CorroborationKey, List<WeightedFeedback>> groups = weighted.stream()
                .collect(Collectors.groupingBy(WeightedFeedback::key));

        int groupsTrusted = 0;
        int reputationEmitted = 0;
        List<RetrainLabel> labels = new ArrayList<>();
        for (Map.Entry<CorroborationKey, List<WeightedFeedback>> entry : groups.entrySet()) {
            CorroborationKey key = entry.getKey();
            List<WeightedFeedback> group = entry.getValue();
            CorroborationResult result = CorroborationGate.evaluate(
                    group, props.minCorroborators(), props.minWeight());
            meter.recordGroup(key, result.trusted());
            if (!result.trusted()) {
                continue;
            }
            // The corroboration verdict is a property of the feedback, so it is reported on every
            // gate of the run; emission, however, happens at most once. A re-gate claims the same
            // key and skips the writes, so reputation is not double-counted nor labels duplicated.
            groupsTrusted++;
            if (!ledger.claim(GATE_GROUP, claimKey(runId, key))) {
                log.debug("group {} of run {} already gated; skipping duplicate emission", key, runId);
                continue;
            }
            emitReputation(key, result);
            reputationEmitted++;
            labels.addAll(labelsFor(runId, key, result, group));
        }
        retrainLabels.saveAll(labels);
        meter.recordLabelsEmitted(labels.size());

        GateOutcome outcome = new GateOutcome(runId, events.size(), weighted.size(),
                groups.size(), groupsTrusted, groups.size() - groupsTrusted, reputationEmitted, labels.size());
        log.info("gated run {}: events={} signals={} groups={} trusted={} reputation={} labels={}",
                runId, outcome.feedbackEvents(), outcome.signalEvents(), outcome.groupsConsidered(),
                outcome.groupsTrusted(), outcome.reputationEventsEmitted(), outcome.retrainLabelsEmitted());
        return outcome;
    }

    /** Loads every email referenced by the run's events, keyed by id, for sender/bucket resolution. */
    private Map<UUID, Email> loadEmails(List<FeedbackEvent> events) {
        List<UUID> emailIds = events.stream().map(FeedbackEvent::emailId).distinct().toList();
        return emails.findByIds(emailIds).stream().collect(Collectors.toMap(Email::id, Function.identity()));
    }

    /**
     * Resolves and weights one event, or empty when it carries no signal ({@code IGNORE}) or its
     * email is missing (defensive — the FK makes that unreachable in practice).
     */
    private Optional<WeightedFeedback> weigh(
            FeedbackEvent event, Map<UUID, Persona> personasById, Map<UUID, Email> emailsById) {
        Optional<ReputationSignal> signal = FeedbackSignal.of(event.action());
        if (signal.isEmpty()) {
            return Optional.empty();
        }
        Email email = emailsById.get(event.emailId());
        if (email == null) {
            log.warn("no email {} for feedback event {}; skipping", event.emailId(), event.id());
            return Optional.empty();
        }
        Persona persona = personasById.get(event.personaId());
        if (persona == null) {
            log.warn("no persona {} for feedback event {}; skipping", event.personaId(), event.id());
            return Optional.empty();
        }
        String senderKey = SenderKey.of(email.metadata().sender(), email.metadata().senderDomain());
        ReputationBucket bucket = AuthGate.bucketFor(
                EmailFeatureExtractor.authFeatures(email.metadata().authResults()));
        double trust = FeedbackWeighting.trust(persona, props.maliciousTrust());
        double weight = FeedbackWeighting.weight(trust, event.confidence());
        return Optional.of(new WeightedFeedback(event.emailId(), event.personaId(), senderKey,
                signal.get(), bucket, event.groundTruth(), event.confidence(), trust, weight));
    }

    /** Appends one reputation event for a trusted group: the corroborated weight, capped for bounded impact. */
    private void emitReputation(CorroborationKey key, CorroborationResult result) {
        double weight = Math.min(result.aggregateWeight(), props.maxReputationWeight());
        reputation.record(key.senderKey(), key.signal(), weight, SIGNAL_SOURCE, key.bucket());
        meter.recordReputationEmitted();
    }

    /** One retrain label per contributing item, each carrying the group's corroboration provenance. */
    private List<RetrainLabel> labelsFor(
            UUID runId, CorroborationKey key, CorroborationResult result, List<WeightedFeedback> group) {
        List<RetrainLabel> labels = new ArrayList<>(group.size());
        for (WeightedFeedback item : group) {
            if (item.weight() <= 0) {
                // A zero-weight example carries no training signal; the sink rejects it anyway.
                continue;
            }
            labels.add(new RetrainLabel(UUID.randomUUID(), item.emailId(), item.groundTruth(),
                    item.weight(), SIGNAL_SOURCE, provenance(runId, key, result, item)));
        }
        return labels;
    }

    /** The claim key for a trusted group within a run: stable across re-gates, unique per group. */
    private static String claimKey(UUID runId, CorroborationKey key) {
        return runId + "|" + key.senderKey() + "|" + key.signal() + "|" + key.bucket();
    }

    /** The per-item audit trail written to the label sink (AC 4/AC 5). */
    private String provenance(
            UUID runId, CorroborationKey key, CorroborationResult result, WeightedFeedback item) {
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("runId", runId.toString());
        provenance.put("senderKey", key.senderKey());
        provenance.put("signal", key.signal().name());
        provenance.put("bucket", key.bucket().name());
        provenance.put("corroborators", result.corroborators());
        provenance.put("aggregateWeight", result.aggregateWeight());
        provenance.put("personaId", item.personaId().toString());
        provenance.put("confidence", item.confidence());
        provenance.put("trust", item.trust());
        provenance.put("itemWeight", item.weight());
        try {
            return objectMapper.writeValueAsString(provenance);
        } catch (JsonProcessingException e) {
            // The map holds only strings and primitives; a failure here is a programming error.
            throw new IllegalStateException("failed to serialize label provenance", e);
        }
    }
}
