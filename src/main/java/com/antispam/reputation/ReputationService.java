package com.antispam.reputation;

import com.antispam.reputation.cache.ReputationReadCache;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The reputation entry point: turns a reputation-affecting signal into an appended
 * event and a refreshed score, and answers "what is this sender's reputation now?"
 * (stories 03.01–03.03).
 *
 * <p><b>Events are the source of truth.</b> Postgres {@code reputation_events} is
 * authoritative; everything else is a derived, rebuildable cache. {@link #record}
 * recomputes the score by summing the append-only log via
 * {@link ReputationRepository#countsFor}, so "recompute from events == current score"
 * holds by construction (AC 4 / AC 5).
 *
 * <p><b>Reads are served from the Redis materialized cache</b> (story 03.04). A read
 * takes the sender's folded {@link CachedReputation} snapshot from
 * {@link ReputationReadCache}, ages it to now with read-time decay, and builds the
 * gated view — no Postgres on the hot path. On a miss (cold entry or a flushed cache)
 * it rebuilds by replaying that sender's events from Postgres and re-populates, yielding
 * the identical score; on a Redis outage the cache reports a miss, so reads transparently
 * fall back to Postgres-backed computation rather than returning wrong/empty reputation.
 * {@link #rebuildCacheFromEvents} reconstructs the whole cache from the log.
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
 * <p><b>Atomicity.</b> {@code record} appends the event and refreshes the Postgres
 * {@code senders} score in one transaction, then refreshes the Redis snapshot only
 * <em>after</em> that transaction commits — so a rollback can never leave the cache
 * ahead of the source of truth. Concurrent accrual for one sender serializes on its
 * Kafka partition (story 03.05); under at-least-once delivery the caller (a consumer /
 * the feedback path, Epic 07) claims the message in the processed-message ledger before
 * calling {@code record} so a redelivery is not double-counted (story 02.03).
 */
@Service
public class ReputationService {

    private static final Logger log = LoggerFactory.getLogger(ReputationService.class);

    private final ReputationRepository repository;
    private final ReputationProperties priors;
    private final Clock clock;
    private final ReputationReadCache cache;

    @Autowired
    public ReputationService(
            ReputationRepository repository,
            ReputationProperties priors,
            Clock clock,
            ReputationReadCache cache) {
        this.repository = repository;
        this.priors = priors;
        this.clock = clock;
        this.cache = cache;
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
        Instant now = clock.instant();
        BucketedReputationCounts counts = repository.countsFor(senderKey, now, priors.decay());
        GatedReputation reputation = GatedReputation.from(counts, priors);
        BetaReputation earned = reputation.authenticated();
        repository.saveScore(senderKey, earned.mean());
        // Refresh the Redis snapshot only once the DB transaction commits, so a rollback
        // never leaves the cache ahead of the event log.
        afterCommit(() -> cache.put(senderKey, new CachedReputation(counts, now)));
        log.info("recorded reputation signal sender={} signal={} weight={} source={} bucket={} -> authMean={} authN={}",
                senderKey, signal, weight, source, bucket, earned.mean(), earned.count());
        return reputation;
    }

    /**
     * The sender's gated reputation — both the authenticated and neutral-capped
     * unauthenticated views — as of now. Served from the Redis snapshot when present,
     * else rebuilt from the event log. An unseen sender returns the prior in both views
     * (a wide, uncertain Beta), never a 404 or a falsely-confident neutral.
     */
    public GatedReputation gatedReputation(String senderKey) {
        return GatedReputation.from(currentCounts(senderKey), priors);
    }

    /**
     * The reputation an email with the given DMARC alignment is entitled to: the full
     * authenticated view when aligned, otherwise the neutral-capped unauthenticated view.
     * This is the read the live decision path uses so a spoofed message is scored on the
     * capped bucket alone, not the domain's earned trust.
     */
    public BetaReputation reputationFor(String senderKey, boolean dmarcAligned) {
        return gatedReputation(senderKey).forAuthStatus(dmarcAligned);
    }

    /**
     * The sender's earned (authenticated-bucket) reputation — the answer to "what trust
     * has this sender legitimately built?", isolated from unauthenticated traffic.
     */
    public BetaReputation currentReputation(String senderKey) {
        return gatedReputation(senderKey).authenticated();
    }

    /**
     * Reconstructs the entire Redis cache from the Postgres event log: for every sender
     * with events, replays them (with decay) as of now and re-populates the snapshot.
     * Used to warm a cold cache or repair after a flush (AC 3). Returns the number of
     * senders rebuilt.
     */
    @Transactional(readOnly = true)
    public int rebuildCacheFromEvents() {
        Instant now = clock.instant();
        List<String> senders = repository.allSenderKeys();
        for (String senderKey : senders) {
            BucketedReputationCounts counts = repository.countsFor(senderKey, now, priors.decay());
            cache.put(senderKey, new CachedReputation(counts, now));
        }
        log.info("rebuilt reputation cache from events for {} senders", senders.size());
        return senders.size();
    }

    /**
     * The sender's current bucketed counts as of now: the cached snapshot aged forward
     * with read-time decay when present, otherwise replayed from the event log and the
     * snapshot re-populated. A cache miss and a Redis outage are indistinguishable here —
     * both yield a Postgres rebuild — which is exactly the graceful degradation we want.
     */
    private BucketedReputationCounts currentCounts(String senderKey) {
        Instant now = clock.instant();
        Optional<CachedReputation> cached = cache.get(senderKey);
        if (cached.isPresent()) {
            return cached.get().decayedTo(now, priors.decay());
        }
        BucketedReputationCounts counts = repository.countsFor(senderKey, now, priors.decay());
        cache.put(senderKey, new CachedReputation(counts, now));
        return counts;
    }

    /** Runs {@code action} after the current transaction commits, or immediately if none is active. */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
