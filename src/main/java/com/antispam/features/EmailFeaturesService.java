package com.antispam.features;

import com.antispam.idempotency.ProcessedMessageLedger;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Extracts and stores versioned features for an email, and reads them back. This
 * is the seam between the event consumer (which knows only an email id) and the
 * extractor + repository: it loads the canonical record, extracts at the current
 * {@link EmailFeatureExtractor#FEATURE_VERSION}, and persists the row.
 *
 * <p><b>Idempotency (story 02.03).</b> Kafka delivers at-least-once, so the same
 * email can be handed to this consumer more than once. Before doing any work the
 * service {@linkplain ProcessedMessageLedger#claim claims} the email in the
 * processed-message ledger; a redelivery loses the claim and is skipped. The claim
 * and the feature write share one transaction (see {@link #extractAndStore}), so a
 * crash between them rolls back the claim and the message is reprocessed rather than
 * recorded as done without its row.
 */
@Service
public class EmailFeaturesService {

    /**
     * This consumer's dedupe scope in the {@link ProcessedMessageLedger}. A stable
     * constant, deliberately decoupled from the (configurable) Kafka group id:
     * renaming the broker-side consumer group must not silently reset which emails
     * have already been processed.
     */
    static final String CONSUMER_GROUP = "feature-extractor";

    private static final Logger log = LoggerFactory.getLogger(EmailFeaturesService.class);

    private final EmailRepository emails;
    private final EmailFeaturesRepository features;
    private final EmailFeatureExtractor extractor;
    private final ProcessedMessageLedger ledger;

    @Autowired
    public EmailFeaturesService(EmailRepository emails, EmailFeaturesRepository features,
            EmailFeatureExtractor extractor, ProcessedMessageLedger ledger) {
        this.emails = emails;
        this.features = features;
        this.extractor = extractor;
        this.ledger = ledger;
    }

    /**
     * Extracts and persists features for the given email id, exactly once per email
     * under at-least-once delivery.
     *
     * <p>If the email is not found (e.g. an event arrived before its row is visible,
     * or it references a since-purged id) this logs and returns empty <em>without
     * claiming the ledger</em>, so a single unresolvable event never stalls the
     * partition and a later, valid redelivery of a now-visible email is still
     * processed. Otherwise the ledger is claimed; a lost claim (a redelivery already
     * processed by this consumer) returns empty and writes nothing. The claim and the
     * feature write run in one transaction so they commit or roll back together.
     *
     * @return the stored features on first delivery, or empty if the email could not
     *     be loaded or the delivery was a duplicate
     */
    @Transactional
    public Optional<EmailFeatures> extractAndStore(UUID emailId) {
        Optional<Email> email = emails.findById(emailId);
        if (email.isEmpty()) {
            log.warn("no email found for id={}; skipping feature extraction", emailId);
            return Optional.empty();
        }
        if (!ledger.claim(CONSUMER_GROUP, emailId.toString())) {
            log.debug("id={} already processed by {}; skipping duplicate delivery", emailId, CONSUMER_GROUP);
            return Optional.empty();
        }
        EmailFeatures extracted = extractor.extract(email.get());
        features.save(extracted);
        log.info("extracted features for id={} version={}", emailId, extracted.featureVersion());
        return Optional.of(extracted);
    }

    /** Returns the current-version features for an email, if they have been extracted. */
    public Optional<EmailFeatures> findCurrent(UUID emailId) {
        return features.find(emailId, EmailFeatureExtractor.FEATURE_VERSION);
    }
}
