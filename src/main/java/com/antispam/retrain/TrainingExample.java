package com.antispam.retrain;

import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * One row of the labeled-data training export (story 10.01): a single weighted, provenanced training
 * example pulled from Postgres for the next retrain (PRD §Subsystem 9 step 1). It unifies the three
 * label sources the retrain learns from — high-confidence seed labels, weighted/corroborated simulator
 * feedback (story 07.03), and arena ground truth (story 08.04) — behind one shape, so the training step
 * (10.02) consumes a single stream while still seeing where each example came from and how much to
 * trust it.
 *
 * <p>The {@code weight} carries straight through from each source: 1.0 for a seed label (ground truth,
 * full confidence), the corroboration gate's capped weight for feedback, and the configured corpus
 * weight for arena. {@code featureVersion} ties every example to the feature schema in force
 * ({@link com.antispam.features.EmailFeatureExtractor#FEATURE_VERSION}), so a retrain is pinned to a
 * known feature contract. Examples on the golden eval side are never exported (no leakage — the export
 * filters them out, coordinated with Epic 11's grouped/time-forward split).
 *
 * <p><b>De-identified for export (story 14.04).</b> The example carries a
 * {@code senderPseudonym} — a stable keyed-HMAC of the sender identity — instead of the real
 * sender, so a copied training artifact never exposes who sent the mail while same-sender rows
 * still group together for grouped/time-forward splits. The {@code provenance} is likewise
 * sanitized at the export boundary (sender pseudonymized, stray addresses masked), and raw
 * bodies are not exported at all.
 *
 * @param emailId        the labeled email (a seed email, a decided email, or an arena variant's email)
 * @param label          the training label — the email's class
 * @param weight         how much this example counts in training; {@code > 0}
 * @param source         where the label came from: {@code seed}, {@code feedback}, or {@code arena}
 * @param provenance     the per-example audit trail as a JSON-object string, de-identified for export
 * @param featureVersion the feature schema version this example is tied to; {@code > 0}
 * @param senderPseudonym the keyed-HMAC pseudonym of the sender (stable per sender); never blank
 */
public record TrainingExample(
        UUID emailId,
        GroundTruthLabel label,
        double weight,
        String source,
        String provenance,
        int featureVersion,
        String senderPseudonym) {

    public TrainingExample {
        if (emailId == null) {
            throw new IllegalArgumentException("emailId is required");
        }
        if (label == null) {
            throw new IllegalArgumentException("label is required");
        }
        if (weight <= 0 || Double.isNaN(weight)) {
            throw new IllegalArgumentException("weight must be positive but was " + weight);
        }
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        if (provenance == null || provenance.isBlank()) {
            throw new IllegalArgumentException("provenance must not be blank");
        }
        if (featureVersion <= 0) {
            throw new IllegalArgumentException("featureVersion must be positive but was " + featureVersion);
        }
        if (senderPseudonym == null || senderPseudonym.isBlank()) {
            throw new IllegalArgumentException("senderPseudonym must not be blank");
        }
    }
}
