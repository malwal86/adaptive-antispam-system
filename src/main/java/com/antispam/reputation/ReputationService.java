package com.antispam.reputation;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reputation entry point: turns a reputation-affecting signal into an appended
 * event and a refreshed Beta score, and answers "what is this sender's reputation
 * now?" (story 03.01).
 *
 * <p><b>Events are the source of truth.</b> Both {@link #record} and
 * {@link #currentReputation} derive the score by summing the append-only log via
 * {@link ReputationRepository#countsFor} and applying the configured prior — never
 * by reading the cache. So the read always reflects the truth, the
 * {@code senders.current_reputation_score} cache that {@code record} writes is only
 * ever a convenience, and "recompute from events == current score" holds by
 * construction (AC 4 / AC 5). The Redis read cache arrives in story 03.04.
 *
 * <p><b>Decay is lazy and read-time</b> (story 03.02). Every summation passes the
 * current {@link Clock} instant and the configured {@link ExponentialDecay} to
 * {@code countsFor}, which fades each event by its age before totalling — so a
 * sender's accrued trust decays simply by time passing, with no decay cron, and the
 * same instant-and-model is used whether the caller is reading or has just recorded.
 * The clock is injected so tests can drive a synthetic timeline.
 *
 * <p><b>Atomicity.</b> {@code record} appends the event and refreshes the cache in
 * one transaction, so the cache never reflects an event that rolled back. Concurrent
 * accrual for one sender serializes on its Kafka partition (story 03.05); under
 * at-least-once delivery the caller (a consumer / the feedback path, Epic 07) claims
 * the message in the processed-message ledger before calling {@code record} so a
 * redelivery is not double-counted (story 02.03).
 */
@Service
public class ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);

    private final ReputationRepository repository;
    private final ReputationProperties priors;
    private final Clock clock;

    @Autowired
    public ReputationService(ReputationRepository repository, ReputationProperties priors, Clock clock) {
        this.repository = repository;
        this.priors = priors;
        this.clock = clock;
    }

    /**
     * Records one signal for a sender and returns the sender's reputation after it.
     *
     * @param senderKey the sender identity (com.antispam.event.SenderKey)
     * @param signal    good or bad
     * @param weight    how much the signal counts ({@code > 0}; 1.0 until auth-gating)
     * @param source    provenance of the signal (e.g. {@code decision}, {@code api})
     * @return the recomputed Beta reputation including the just-recorded signal
     */
    @Transactional
    public BetaReputation record(String senderKey, ReputationSignal signal, double weight, String source) {
        // Append, then recompute as of now: prior events are decayed to this instant
        // and the just-appended event (age ~0) folds in at full weight, so the write
        // path's decay matches the read path's by construction (AC 3).
        repository.append(ReputationEvent.of(senderKey, signal, weight, source));
        BetaReputation reputation = computeFromEvents(senderKey, clock.instant());
        repository.saveScore(senderKey, reputation.mean());
        log.info("recorded reputation signal sender={} signal={} weight={} source={} -> mean={} n={}",
                senderKey, signal, weight, source, reputation.mean(), reputation.count());
        return reputation;
    }

    /**
     * The sender's current reputation, recomputed from the event log with evidence
     * decayed to the present instant. An unseen sender returns the prior — a wide,
     * uncertain Beta — rather than a 404 or a falsely-confident neutral.
     */
    @Transactional(readOnly = true)
    public BetaReputation currentReputation(String senderKey) {
        return computeFromEvents(senderKey, clock.instant());
    }

    private BetaReputation computeFromEvents(String senderKey, Instant readAt) {
        ReputationCounts counts = repository.countsFor(senderKey, readAt, priors.decay());
        return new BetaReputation(counts.good(), counts.bad(), priors.alpha(), priors.beta());
    }
}
