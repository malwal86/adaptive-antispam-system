package com.antispam.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.web.JsonIngestRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end ingest over HTTP: POST a raw email, get back a canonical id, and
 * retrieve the message byte-for-byte intact. Also covers the JSON envelope,
 * idempotent re-POST, and the input/Not-Found error contracts.
 */
@AutoConfigureMockMvc
class EmailIngestApiTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private byte[] sampleEmail() throws Exception {
        try (var in = new ClassPathResource("sample-emails/spamassassin-sample.eml").getInputStream()) {
            return in.readAllBytes();
        }
    }

    @Test
    void posting_raw_email_creates_record_and_get_returns_it_intact() throws Exception {
        byte[] raw = sampleEmail();

        MvcResult posted = mockMvc.perform(post("/emails")
                        .contentType("message/rfc822")
                        .content(raw))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.emailId").exists())
                .andExpect(jsonPath("$.duplicate").value(false))
                .andReturn();

        String emailId = idFrom(posted);

        // Default JSON view is redacted: sender masked, domain kept, no raw body.
        mockMvc.perform(get("/emails/{id}", emailId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("n***@deals.example.net"))
                .andExpect(jsonPath("$.senderDomain").value("deals.example.net"))
                .andExpect(jsonPath("$.subject").value("You won! Claim your prize"))
                .andExpect(jsonPath("$.authResults").value(org.hamcrest.Matchers.containsString("spf=pass")))
                .andExpect(jsonPath("$.rawBase64").doesNotExist());

        // Opt-in reveal returns the full, byte-faithful record.
        mockMvc.perform(get("/emails/{id}", emailId).param("reveal", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("newsletter@deals.example.net"))
                .andExpect(jsonPath("$.rawBase64").value(Base64.getEncoder().encodeToString(raw)));

        // Raw view: byte-identical to what was posted.
        byte[] fetchedRaw = mockMvc.perform(get("/emails/{id}/raw", emailId))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(fetchedRaw).isEqualTo(raw);
    }

    @Test
    void posting_json_envelope_ingests_the_raw_message() throws Exception {
        String raw = "From: json@example.com\nSubject: via json\n\nbody\n";
        String body = objectMapper.writeValueAsString(new JsonIngestRequest(raw, "json-test"));

        MvcResult posted = mockMvc.perform(post("/emails")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("json-test"))
                .andReturn();

        mockMvc.perform(get("/emails/{id}", idFrom(posted)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sender").value("j***@example.com"));
    }

    @Test
    void re_posting_identical_bytes_returns_200_with_the_same_id() throws Exception {
        byte[] raw = "From: dup@example.com\nSubject: dup\n\nbody\n".getBytes(StandardCharsets.UTF_8);

        String firstId = idFrom(mockMvc.perform(post("/emails").contentType("message/rfc822").content(raw))
                .andExpect(status().isCreated()).andReturn());

        mockMvc.perform(post("/emails").contentType("message/rfc822").content(raw))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true))
                .andExpect(jsonPath("$.emailId").value(firstId));
    }

    @Test
    void posting_empty_body_returns_400() throws Exception {
        mockMvc.perform(post("/emails").contentType("message/rfc822").content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getting_unknown_id_returns_404() throws Exception {
        mockMvc.perform(get("/emails/{id}", "00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    private String idFrom(MvcResult result) throws Exception {
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
        return node.get("emailId").asText();
    }
}
