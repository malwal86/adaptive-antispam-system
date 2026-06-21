package com.antispam.features.web;

import com.antispam.features.EmailFeatures;
import com.antispam.features.FeatureSet;
import java.time.Instant;
import java.util.UUID;

/**
 * API view of an email's extracted features. The feature set is entirely
 * non-PII (counts, ratios, flags, and auth-result tokens), so it is returned as
 * is — there is no raw content or address to redact here.
 *
 * @param emailId        the email these features describe
 * @param featureVersion the extractor version that produced them
 * @param features       the extracted signals
 * @param extractedAt    when the row was written
 */
public record EmailFeaturesResponse(
        UUID emailId,
        int featureVersion,
        FeatureSet features,
        Instant extractedAt) {

    public static EmailFeaturesResponse from(EmailFeatures features) {
        return new EmailFeaturesResponse(
                features.emailId(),
                features.featureVersion(),
                features.features(),
                features.extractedAt());
    }
}
