package com.antispam.features;

import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Extracts and stores versioned features for an email, and reads them back. This
 * is the seam between the event consumer (which knows only an email id) and the
 * extractor + repository: it loads the canonical record, extracts at the current
 * {@link EmailFeatureExtractor#FEATURE_VERSION}, and persists the row.
 */
@Service
public class EmailFeaturesService {

    private static final Logger log = LoggerFactory.getLogger(EmailFeaturesService.class);

    private final EmailRepository emails;
    private final EmailFeaturesRepository features;
    private final EmailFeatureExtractor extractor;

    @Autowired
    public EmailFeaturesService(EmailRepository emails, EmailFeaturesRepository features,
            EmailFeatureExtractor extractor) {
        this.emails = emails;
        this.features = features;
        this.extractor = extractor;
    }

    /**
     * Extracts and persists features for the given email id.
     *
     * <p>If the email is not found (e.g. an event arrived before its row is
     * visible, or it references a since-purged id) this logs and returns empty
     * rather than throwing, so a single unresolvable event never stalls the
     * partition. The store itself is an idempotent upsert, so re-processing the
     * same event is safe.
     *
     * @return the stored features, or empty if the email could not be loaded
     */
    public Optional<EmailFeatures> extractAndStore(UUID emailId) {
        Optional<Email> email = emails.findById(emailId);
        if (email.isEmpty()) {
            log.warn("no email found for id={}; skipping feature extraction", emailId);
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
