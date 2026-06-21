package com.antispam.reputation.accrual;

import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.DecisionService;
import com.antispam.event.SenderKey;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.antispam.reputation.AuthGate;
import com.antispam.reputation.ReputationBucket;
import com.antispam.reputation.ReputationService;
import com.antispam.reputation.ReputationSignal;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Drives reputation accrual off the event spine: the seam between the {@link
 * ReputationAccrualConsumer} (which knows only an email id and the partition it
 * arrived on) and the reputation log (story 03.05). For one email it loads the
 * canonical record, derives the verdict its mail earns and the bucket its auth
 * earns, and records exactly one reputation signal for the sender.
 *
 * <p><b>Lock-free by topology, not by mutex.</b> This updater holds no application
 * lock and no mutable per-sender state — every field is an injected, final
 * collaborator. Correctness under concurrent same-sender traffic comes entirely from
 * {@code emails.raw} being keyed by sender (story 02.01): all of a sender's events
 * land on one partition, which exactly one consumer thread owns, so a sender's
 * accruals are applied in order by a single thread with nothing to contend on. Other
 * senders proceed in parallel on other partitions. The accepted demo-scale cost is
 * hot-sender partition skew, surfaced by {@link PartitionSkewMeter}.
 *
 * <p><b>Idempotency (story 02.03).</b> Delivery is at-least-once — a redelivery on
 * retry, rebalance, or replay (Epic 09) must not double-count. Before recording, the
 * service {@linkplain ProcessedMessageLedger#claim claims} the email under its own
 * {@value #CONSUMER_GROUP} scope; a lost claim is a duplicate and records nothing.
 * The claim and the reputation write share one transaction (see {@link #accrue}), so
 * a crash between them rolls back the claim and the message is reprocessed rather
 * than recorded as done without its signal. This scope is independent of the feature
 * extractor's, so the two consumers dedupe the same email separately.
 *
 * <p><b>Signal derivation.</b> The verdict comes from {@link DecisionService#evaluate}
 * (read-only — no second {@link com.antispam.decision.Classification} is minted),
 * mapped to good/bad by {@link DecisionSignal}; the bucket comes from the email's
 * {@code Authentication-Results} via {@link AuthGate}, so a spoofed message accrues
 * to the neutral-capped unauthenticated bucket, never a domain's earned trust
 * (story 03.03). Everything is derived from the {@link Email} alone, so accrual has
 * no ordering dependency on the feature extractor running first.
 */
@Service
public class ReputationAccrualService {

    /**
     * This consumer's dedupe scope in the {@link ProcessedMessageLedger}. A stable
     * constant, deliberately decoupled from the (configurable) Kafka group id, so
     * renaming the broker-side group never silently resets which emails have accrued.
     */
    static final String CONSUMER_GROUP = "reputation-accrual";

    /** Provenance stamped on every accrued event — these signals come from the decision path. */
    static final String SIGNAL_SOURCE = "decision";

    /** Weight of one decision-derived observation: a single, unit-weight signal. */
    static final double SIGNAL_WEIGHT = 1.0;

    private static final Logger log = LoggerFactory.getLogger(ReputationAccrualService.class);

    private final EmailRepository emails;
    private final DecisionService decisionService;
    private final ReputationService reputationService;
    private final ProcessedMessageLedger ledger;
    private final PartitionSkewMeter skewMeter;

    @Autowired
    public ReputationAccrualService(
            EmailRepository emails,
            DecisionService decisionService,
            ReputationService reputationService,
            ProcessedMessageLedger ledger,
            PartitionSkewMeter skewMeter) {
        this.emails = emails;
        this.decisionService = decisionService;
        this.reputationService = reputationService;
        this.ledger = ledger;
        this.skewMeter = skewMeter;
    }

    /**
     * Accrues the reputation signal for one email, exactly once under at-least-once
     * delivery.
     *
     * <p>If the email is not found (an event arrived before its row is visible, or it
     * references a since-purged id) this logs and returns {@code false} <em>without
     * claiming the ledger</em>, so a single unresolvable event never stalls the
     * partition and a later, valid redelivery of a now-visible email still accrues.
     * Otherwise the ledger is claimed; a lost claim (a redelivery already accrued)
     * returns {@code false} and records nothing. The claim and the reputation write
     * run in one transaction so they commit or roll back together.
     *
     * @param emailId   the email to accrue for
     * @param partition the Kafka partition it was delivered on — recorded for skew only
     * @return {@code true} if a signal was recorded, {@code false} if skipped (unknown
     *     email or duplicate delivery)
     */
    @Transactional
    public boolean accrue(UUID emailId, int partition) {
        Optional<Email> found = emails.findById(emailId);
        if (found.isEmpty()) {
            log.warn("no email found for id={}; skipping reputation accrual", emailId);
            return false;
        }
        if (!ledger.claim(CONSUMER_GROUP, emailId.toString())) {
            log.debug("id={} already accrued by {}; skipping duplicate delivery", emailId, CONSUMER_GROUP);
            return false;
        }

        Email email = found.get();
        String senderKey = SenderKey.of(email.metadata().sender(), email.metadata().senderDomain());
        DecisionOutcome outcome = decisionService.evaluate(email);
        ReputationSignal signal = DecisionSignal.of(outcome.decision());
        ReputationBucket bucket = AuthGate.bucketFor(
                EmailFeatureExtractor.authFeatures(email.metadata().authResults()));

        reputationService.record(senderKey, signal, SIGNAL_WEIGHT, SIGNAL_SOURCE, bucket);
        skewMeter.record(partition);
        log.info("accrued reputation id={} sender={} decision={} signal={} bucket={} partition={}",
                emailId, senderKey, outcome.decision(), signal, bucket, partition);
        return true;
    }
}
