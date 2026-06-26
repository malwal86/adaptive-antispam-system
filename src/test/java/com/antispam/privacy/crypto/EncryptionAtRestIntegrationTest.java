package com.antispam.privacy.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.Email;
import com.antispam.ingest.EmailRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Encryption at rest + crypto-shredding against the real database. Runs with a
 * master key configured (most of the suite runs encryption-disabled, exercising
 * the plaintext-passthrough path), so this class proves the encrypted path:
 * ciphertext in the column, transparent decrypt on read, and erasure that
 * destroys the key while leaving the immutable row intact.
 */
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "antispam.encryption.active-key-version=test",
        // base64 of a 32-byte AES key; test-only material, never a real key.
        "antispam.encryption.keys.test=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY="
})
class EncryptionAtRestIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private EmailRepository emails;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void stored_body_is_ciphertext_in_the_column_but_reads_back_byte_faithful() throws Exception {
        byte[] raw = uniqueEmail("cipher");

        UUID id = ingest(raw);

        // The column holds ciphertext, not the plaintext bytes.
        byte[] atRest = jdbc.queryForObject(
                "select raw_content from emails where id = ?", byte[].class, id);
        assertThat(atRest).isNotEqualTo(raw);

        // A key row exists, wrapped under the active master key version.
        String version = jdbc.queryForObject(
                "select master_key_version from email_content_keys where email_id = ?", String.class, id);
        assertThat(version).isEqualTo("test");

        // The repository read transparently decrypts to the exact original bytes.
        Email loaded = emails.findById(id).orElseThrow();
        assertThat(loaded.rawContent()).isEqualTo(raw);
        assertThat(loaded.contentErased()).isFalse();
    }

    @Test
    void reveal_returns_decrypted_body_default_read_stays_masked() throws Exception {
        byte[] raw = uniqueEmail("reveal");
        UUID id = ingest(raw);

        mockMvc.perform(get("/emails/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawBase64").doesNotExist())
                .andExpect(jsonPath("$.sender").value(org.hamcrest.Matchers.startsWith("a***@")));

        mockMvc.perform(get("/emails/{id}", id).param("reveal", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawBase64")
                        .value(java.util.Base64.getEncoder().encodeToString(raw)));

        byte[] fetched = mockMvc.perform(get("/emails/{id}/raw", id))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(fetched).isEqualTo(raw);
    }

    @Test
    void erasure_destroys_the_key_renders_content_unrecoverable_and_leaves_the_row() throws Exception {
        byte[] raw = uniqueEmail("erase");
        UUID id = ingest(raw);

        mockMvc.perform(post("/emails/{id}/erasure", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ERASED"));

        // The key is gone; the column ciphertext remains but is now meaningless.
        byte[] wrappedDek = jdbc.queryForObject(
                "select wrapped_dek from email_content_keys where email_id = ?", byte[].class, id);
        assertThat(wrappedDek).isNull();

        // The immutable row still exists and is auditable — erasure did not delete it.
        assertThat(emails.existsById(id)).isTrue();

        // Reads now report the erased state, never plaintext.
        Email loaded = emails.findById(id).orElseThrow();
        assertThat(loaded.contentErased()).isTrue();

        mockMvc.perform(get("/emails/{id}", id).param("reveal", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.contentErased").value(true))
                .andExpect(jsonPath("$.rawBase64").doesNotExist());

        mockMvc.perform(get("/emails/{id}/raw", id))
                .andExpect(status().isGone());
    }

    @Test
    void erasing_an_already_erased_email_is_idempotent() throws Exception {
        UUID id = ingest(uniqueEmail("twice"));

        mockMvc.perform(post("/emails/{id}/erasure", id))
                .andExpect(jsonPath("$.outcome").value("ERASED"));
        mockMvc.perform(post("/emails/{id}/erasure", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("ALREADY_ERASED"));
    }

    @Test
    void erasing_an_unknown_email_returns_404() throws Exception {
        mockMvc.perform(post("/emails/{id}/erasure", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void the_emails_row_remains_immutable_after_erasure() throws Exception {
        UUID id = ingest(uniqueEmail("immutable"));
        mockMvc.perform(post("/emails/{id}/erasure", id)).andExpect(status().isOk());

        // The crypto-shred lives entirely in the key store; the canonical row itself
        // is still write-protected by the V1 immutability trigger.
        assertThatThrownBy(() -> jdbc.update(
                "update emails set ingest_source = 'tamper' where id = ?", id))
                .isInstanceOf(DataAccessException.class);
    }

    private UUID ingest(byte[] raw) throws Exception {
        MvcResult posted = mockMvc.perform(post("/emails")
                        .contentType("message/rfc822").content(raw))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(posted.getResponse().getContentAsString());
        return UUID.fromString(node.get("emailId").asText());
    }

    /** A unique sample so each test ingests a distinct row (idempotency is on the body hash). */
    private static byte[] uniqueEmail(String tag) {
        return ("From: alice@example.com\r\nSubject: " + tag + " " + UUID.randomUUID()
                + "\r\n\r\nbody of " + tag).getBytes(StandardCharsets.UTF_8);
    }
}
