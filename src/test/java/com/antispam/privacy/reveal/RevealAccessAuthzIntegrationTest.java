package com.antispam.privacy.reveal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Server-side enforcement of the redaction-bypassing accessors (story 14.05): without a
 * valid bearer secret, {@code ?reveal=true}, {@code /raw}, and erasure are rejected
 * (401/403) — not merely hidden in the UI — while the masked default read stays open.
 * Every authorized reveal/raw/erasure leaves an audit entry naming who accessed which
 * email.
 */
@AutoConfigureMockMvc
class RevealAccessAuthzIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String VALID_AUTH = "Bearer test-reveal-secret";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RevealAccessAuditRepository audit;

    @Test
    void the_masked_default_read_needs_no_authorization() throws Exception {
        UUID id = ingest("masked");
        mockMvc.perform(get("/emails/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawBase64").doesNotExist());
    }

    @Test
    void reveal_without_credentials_is_unauthorized() throws Exception {
        UUID id = ingest("noauth");
        mockMvc.perform(get("/emails/{id}", id).param("reveal", "true"))
                .andExpect(status().isUnauthorized());
        assertThat(audit.findByEmail(id)).isEmpty();
    }

    @Test
    void reveal_with_a_wrong_token_is_forbidden() throws Exception {
        UUID id = ingest("wrongtoken");
        mockMvc.perform(get("/emails/{id}", id).param("reveal", "true")
                        .header("Authorization", "Bearer not-the-secret"))
                .andExpect(status().isForbidden());
        assertThat(audit.findByEmail(id)).isEmpty();
    }

    @Test
    void raw_without_credentials_is_unauthorized() throws Exception {
        UUID id = ingest("rawnoauth");
        mockMvc.perform(get("/emails/{id}/raw", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void erasure_without_credentials_is_unauthorized() throws Exception {
        UUID id = ingest("erasenoauth");
        mockMvc.perform(post("/emails/{id}/erasure", id))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void an_authorized_reveal_is_permitted_and_recorded_with_the_actor() throws Exception {
        UUID id = ingest("authorized");

        mockMvc.perform(get("/emails/{id}", id).param("reveal", "true")
                        .header("Authorization", VALID_AUTH)
                        .header("X-Reveal-Actor", "alice@ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rawBase64").exists());

        List<RevealAccessAudit> entries = audit.findByEmail(id);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().actor()).isEqualTo("alice@ops");
        assertThat(entries.getFirst().accessType()).isEqualTo("reveal");
        assertThat(entries.getFirst().emailId()).isEqualTo(id);
    }

    @Test
    void an_authorized_raw_access_defaults_the_actor_and_is_recorded() throws Exception {
        UUID id = ingest("rawauth");

        mockMvc.perform(get("/emails/{id}/raw", id).header("Authorization", VALID_AUTH))
                .andExpect(status().isOk());

        List<RevealAccessAudit> entries = audit.findByEmail(id);
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().actor()).isEqualTo("operator");
        assertThat(entries.getFirst().accessType()).isEqualTo("raw");
    }

    private UUID ingest(String tag) throws Exception {
        byte[] raw = ("From: alice@example.com\r\nSubject: " + tag + " " + UUID.randomUUID()
                + "\r\n\r\nbody").getBytes(StandardCharsets.UTF_8);
        MvcResult posted = mockMvc.perform(post("/emails").contentType("message/rfc822").content(raw))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(posted.getResponse().getContentAsString());
        return UUID.fromString(node.get("emailId").asText());
    }
}
