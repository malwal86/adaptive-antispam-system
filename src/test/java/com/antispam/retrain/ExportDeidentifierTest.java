package com.antispam.retrain;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.privacy.SenderPseudonymizer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The export de-identification pass (story 14.04): it must replace a sender identity
 * embedded in provenance with the same stable pseudonym used for grouping, and mask
 * any stray addresses elsewhere — so a copied training artifact carries no raw direct
 * identifiers, while same-sender rows still pseudonymize identically.
 */
class ExportDeidentifierTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SenderPseudonymizer pseudonymizer =
            new SenderPseudonymizer("export-key-0123456789abcdef0123".getBytes(StandardCharsets.UTF_8));
    private final ExportDeidentifier deidentifier = new ExportDeidentifier(pseudonymizer, objectMapper);

    @Test
    void provenance_sender_key_is_replaced_with_the_grouping_pseudonym() {
        String provenance = "{\"runId\":\"r1\",\"senderKey\":\"alice@example.com\",\"corroborators\":3}";

        String sanitized = deidentifier.sanitizeProvenance(provenance);

        assertThat(sanitized)
                .doesNotContain("alice@example.com")
                .contains(deidentifier.pseudonymFor("alice@example.com"))
                .contains("\"corroborators\":3");
    }

    @Test
    void stray_addresses_in_other_provenance_fields_are_masked() {
        String provenance = "{\"note\":\"reported by bob@victim.example\"}";

        assertThat(deidentifier.sanitizeProvenance(provenance))
                .doesNotContain("bob@victim.example")
                .contains("b***@victim.example");
    }

    @Test
    void provenance_without_identifiers_is_unchanged_in_meaning() {
        String provenance = "{\"outcome\":\"bypass\",\"generation\":2}";

        assertThat(deidentifier.sanitizeProvenance(provenance))
                .contains("\"outcome\":\"bypass\"")
                .contains("\"generation\":2");
    }

    @Test
    void the_provenance_pseudonym_matches_the_grouping_pseudonym_for_the_same_sender() {
        String provenance = "{\"senderKey\":\"spammer@bad.example\"}";

        // The pseudonym written into provenance must equal the one the export groups by,
        // so grouping by the embedded value still groups same-sender rows together.
        assertThat(deidentifier.sanitizeProvenance(provenance))
                .contains(deidentifier.pseudonymFor("spammer@bad.example"));
    }
}
