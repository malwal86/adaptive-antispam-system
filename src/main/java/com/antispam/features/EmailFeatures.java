package com.antispam.features;

import java.time.Instant;
import java.util.UUID;

/**
 * A versioned feature record for one email: the {@link FeatureSet} extracted at a
 * specific {@code featureVersion}, keyed by {@code (emailId, featureVersion)} so
 * that re-extracting under a bumped version coexists with the old row rather than
 * overwriting it (story 02.02 AC 3). Both the live classifier and offline retrains
 * read these, so the version stamp is what keeps a retrained model honest about
 * exactly which signals it consumed (PRD §Data Model).
 *
 * @param emailId        the email these features describe (FK to the immutable canonical record)
 * @param featureVersion the extractor contract version that produced {@code features}
 * @param features       the extracted signals (see {@link FeatureSet})
 * @param extractedAt    when the row was written; {@code null} before persistence
 *                       assigns it (the DB stamps it), non-null when read back
 */
public record EmailFeatures(
        UUID emailId,
        int featureVersion,
        FeatureSet features,
        Instant extractedAt) {
}
