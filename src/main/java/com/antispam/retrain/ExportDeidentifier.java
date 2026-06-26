package com.antispam.retrain;

import com.antispam.privacy.Redaction;
import com.antispam.privacy.SenderPseudonymizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * De-identifies a training/eval export at the boundary it leaves the canonical store
 * (story 14.04). Exports get copied to CI and Supabase Storage — outside the
 * controlled store — so direct identifiers are pseudonymized or masked first:
 *
 * <ul>
 *   <li>the sender identity becomes a stable keyed-HMAC {@link #pseudonymFor pseudonym},
 *       so grouped/time-forward splits and reputation lineage (Epic 11) still function
 *       without the export exposing who the sender is;</li>
 *   <li>provenance JSON is rewritten — a {@code senderKey}/{@code sender} field is
 *       replaced with that same pseudonym (so grouping by the embedded value still
 *       works), and any stray email address in another field is masked.</li>
 * </ul>
 *
 * Raw email bodies are not exported at all, so there is nothing to mask there.
 */
@Component
public class ExportDeidentifier {

    /** Provenance fields that hold a sender identity and so are pseudonymized, not just masked. */
    private static final Set<String> SENDER_FIELDS = Set.of("senderKey", "sender");

    private final SenderPseudonymizer pseudonymizer;
    private final ObjectMapper objectMapper;

    @Autowired
    public ExportDeidentifier(SenderPseudonymizer pseudonymizer, ObjectMapper objectMapper) {
        this.pseudonymizer = pseudonymizer;
        this.objectMapper = objectMapper;
    }

    /** The stable pseudonym an export groups a sender by. */
    public String pseudonymFor(String senderKey) {
        return pseudonymizer.pseudonym(senderKey);
    }

    /**
     * Returns {@code provenanceJson} with sender identities pseudonymized and stray
     * addresses masked. The structure and all non-identifying values are preserved, so
     * the training step still reads the same provenance shape.
     */
    public String sanitizeProvenance(String provenanceJson) {
        try {
            JsonNode sanitized = sanitize(objectMapper.readTree(provenanceJson));
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException e) {
            // Provenance is JSON this system wrote, so a parse failure is a programming error.
            throw new IllegalStateException("export provenance was not valid JSON: " + provenanceJson, e);
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (SENDER_FIELDS.contains(name) && value.isTextual()) {
                    out.put(name, pseudonymizer.pseudonym(value.asText()));
                } else {
                    out.set(name, sanitize(value));
                }
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            node.forEach(child -> out.add(sanitize(child)));
            return out;
        }
        if (node.isTextual()) {
            // Defense in depth: mask any address that appears in a non-sender text field.
            return TextNode.valueOf(Redaction.redactEmails(node.asText()));
        }
        return node;
    }
}
