package com.antispam.retrain;

import com.antispam.seed.GroundTruthLabel;
import java.util.List;
import java.util.stream.Stream;

/**
 * The de-identification manifest carried with a labeled-data export (story 14.04): a
 * self-describing record of what was applied to the artifact and which schema/label
 * versions it is tied to, so a copy living in CI or Storage documents its own privacy
 * posture rather than relying on out-of-band knowledge.
 *
 * @param featureVersion          the feature schema every example is tied to
 * @param senderPseudonymization  how sender identity was pseudonymized (keyed HMAC)
 * @param directIdentifiersMasked whether direct identifiers (addresses) were masked in provenance
 * @param rawBodiesExported       whether raw email bodies are included (they are not)
 * @param labelVocabulary         the label tokens that appear in the export
 */
public record ExportManifest(
        int featureVersion,
        String senderPseudonymization,
        boolean directIdentifiersMasked,
        boolean rawBodiesExported,
        List<String> labelVocabulary) {

    public ExportManifest {
        labelVocabulary = List.copyOf(labelVocabulary);
    }

    /** The manifest for the current export posture: HMAC-pseudonymized senders, no raw bodies. */
    public static ExportManifest standard(int featureVersion) {
        return new ExportManifest(
                featureVersion,
                "keyed HMAC-SHA-256",
                true,
                false,
                Stream.of(GroundTruthLabel.values()).map(GroundTruthLabel::dbValue).toList());
    }
}
