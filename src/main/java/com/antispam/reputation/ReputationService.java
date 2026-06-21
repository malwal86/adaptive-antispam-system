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
 * event and a refreshed score, and answers "what is this sender's reputation now?"
 * (stories 03.01–03.03).
 *
 * <p><b>Events are the source of truth.</b> Both {@link #record} and the reads derive
 * the score by summing the append-only log via {@link ReputationRepository#countsFor}
 * and applying the configured prior — never by reading the cache. So the read always
 * reflects the truth, the {@code senders.current_reputation_score} cache that
 * {@code record} writes is only ever a convenience, and "recompute from events ==
 * current score" holds by construction (AC 4 / AC 5). The Redis read cache arrives in
 * story 03.04.
 *
 * <p><b>Decay is lazy and read-time</b> (story 03.02). Every summation passes the
 * current {@link Clock} instant and the configured {@link ExponentialDecay} to
 * {@code countsFor}, which fades each event by its age before totalling — so a
 * sender's accrued trust decays simply by time passing, with no decay cron, and the
 * same instant-and-model is used whether the caller is reading or has just recorded.
 * The clock is injected so tests can drive a synthetic timeline.
 *
 * <p><b>Accrual is soft auth-gated</b> (story 03.03). Each signal carries the
 * {@link ReputationBucket} its mail's DMARC alignment puts it in, and the read returns
 * a {@link GatedReputation} holding both the full authenticated view and the
 * neutral-capped unauthenticated view. A caller scoring a specific email picks the view
 * matching that email's auth status via {@link #reputationFor}, so an unauthenticated
 * (spoofed) message can never inherit a warmed-up domain's trust. The cached score is
 * the authenticated (earned) mean.
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
     * Records one signal for a sender, into the bucket its mail's authentication earns,
     * and returns the sender's gated reputation after it.
     *
     * @param senderKey the sender identity (com.antispam.event.SenderKey)
     * @param signal    good or bad
     * @param weight    how much the signal counts ({@code > 0}; 1.0 by default)
     * @param source    provenance of the signal (e.g. {@code decision}, {@code api})
     * @param bucket    which accrual bucket — {@link AuthGate} maps the email's auth
     *                  result to {@link ReputationBucket#AUTHENTICATED} or
     *                  {@link ReputationBucket#UNAUTHENTICATED}
     * @return the recomputed gated reputation including the just-recorded signal
     */
    @Transactional
    public GatedReputation record(
            String senderKey, ReputationSignal signal, double weight, String source, ReputationBucket bucket) {
        // Append, then recompute as of now: prior events are decayed to this instant
        // and the just-appended event (age ~0) folds in at full weight, so the write
        // path's decay matches the read path's by construction (AC 3).
        repository.append(ReputationEvent.of(senderKey, signal, weight, source, bucket));
        GatedReputation reputation = computeFromEvents(senderKey, clock.instant());
        BetaReputation earned = reputation.authenticated();
        repository.saveScore(senderKey, earned.mean());
        log.info("recorded reputation signal sender={} signal={} weight={} source={} bucket={} -> authMean={} authN={}",
                senderKey, signal, weight, source, bucket, earned.mean(), earned.count());
        return reputation;
    }

    /**
     * The sender's gated reputation — both the authenticated and neutral-capped
     * unauthenticated views — recomputed from the event log with evidence decayed to the
     * present instant. An unseen sender returns the prior in both views (a wide,
     * uncertain Beta), never a 404 or a falsely-confident neutral.
     */
    @Transactional(readOnly = true)
    public GatedReputation gatedReputation(String senderKey) {
        return computeFromEvents(senderKey, clock.instant());
    }

    /**
     * The reputation an email with the given DMARC alignment is entitled to: the full
     * authenticated view when aligned, otherwise the neutral-capped unauthenticated view.
     * This is the read the live decision path uses so a spoofed message is scored on the
     * capped bucket alone, not the domain's earned trust.
     */
    @Transactional(readOnly = true)
    public BetaReputation reputationFor(String senderKey, boolean dmarcAligned) {
        return gatedReputation(senderKey).forAuthStatus(dmarcAligned);
    }

    /**
     * The sender's earned (authenticated-bucket) reputation — the answer to "what trust
     * has this sender legitimately built?", isolated from unauthenticated traffic.
     */
    @Transactional(readOnly = true)
    public BetaReputation currentReputation(String senderKey) {
        return gatedReputation(senderKey).authenticated();
    }

    private GatedReputation computeFromEvents(String senderKey, Instant readAt) {
        BucketedReputationCounts counts = repository.countsFor(senderKey, readAt, priors.decay());
        return GatedReputation.from(counts, priors);
    }
}
