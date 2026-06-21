package com.antispam.reputation;

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

    @Autowired
    public ReputationService(ReputationRepository repository, ReputationProperties priors) {
        this.repository = repository;
        this.priors = priors;
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
        repository.append(ReputationEvent.of(senderKey, signal, weight, source));
        BetaReputation reputation = computeFromEvents(senderKey);
        repository.saveScore(senderKey, reputation.mean());
        log.info("recorded reputation signal sender={} signal={} weight={} source={} -> mean={} n={}",
                senderKey, signal, weight, source, reputation.mean(), reputation.count());
        return reputation;
    }

    /**
     * The sender's current reputation, recomputed from the event log. An unseen
     * sender returns the prior — a wide, uncertain Beta — rather than a 404 or a
     * falsely-confident neutral.
     */
    @Transactional(readOnly = true)
    public BetaReputation currentReputation(String senderKey) {
        return computeFromEvents(senderKey);
    }

    private BetaReputation computeFromEvents(String senderKey) {
        ReputationCounts counts = repository.countsFor(senderKey);
        return new BetaReputation(counts.good(), counts.bad(), priors.alpha(), priors.beta());
    }
}
