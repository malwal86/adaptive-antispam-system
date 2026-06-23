package com.antispam.retrain;

import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * One row of the append-only {@code retrain_labels} log — a single weighted training example for the
 * next classifier (story 07.03, the label sink). Epic 10 builds its training set from these rows, so
 * a label is "this email is of class {@code label}, count it with this {@code weight}, and here is
 * where it came from". Written once and never mutated (the table enforces this).
 *
 * <p>The label is the email's class; the {@code weight} is how much the corroborated feedback trusts
 * it; {@code provenance} is the per-item audit trail (AC 4/AC 5). {@code provenance} is opaque JSON
 * so the sink stays generic across sources — the feedback gate records run/corroboration/persona
 * detail; Epic 10's seed/arena sources will record their own shape without changing this type.
 *
 * @param id         canonical id assigned by the writer; {@code null} before persistence
 * @param emailId    the email this label is about
 * @param label      the training label (the email's true class)
 * @param weight     how much this example counts; {@code > 0}
 * @param source     provenance token ({@code feedback}, later {@code seed}/{@code arena}); non-blank
 * @param provenance per-item audit trail as a JSON object string; non-blank
 */
public record RetrainLabel(
        UUID id,
        UUID emailId,
        GroundTruthLabel label,
        double weight,
        String source,
        String provenance) {

    public RetrainLabel {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
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
    }
}
