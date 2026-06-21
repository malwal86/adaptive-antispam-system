package com.antispam.features;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.kafka.KafkaContainer;

/**
 * End-to-end proof of the feature-extraction slice against a real broker and
 * Postgres: ingesting an email publishes onto {@code emails.raw}, the consumer
 * extracts features, and a versioned {@code email_features} row appears — readable
 * through {@code GET /emails/{id}/features}. Also covers version coexistence
 * (story 02.02 AC 3). Skips on machines without Docker; runs in full in CI.
 */
@TestPropertySource(properties = {
        "app.kafka.enabled=true",
        // A single-broker test cluster cannot satisfy a replication factor of 3.
        "app.kafka.raw-topic.replication-factor=1",
        "app.kafka.raw-topic.partitions=3"
})
@AutoConfigureMockMvc
class FeatureExtractionConsumerIntegrationTest extends AbstractPostgresIntegrationTest {

    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.1");

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            KAFKA.start();
        }
    }

    @Autowired
    private IngestService ingestService;

    @Autowired
    private EmailFeaturesRepository repository;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void ingesting_an_email_extracts_a_versioned_feature_row() throws Exception {
        IngestResult ingested = ingestService.ingest(rawEmail("feature row case"), "api");

        EmailFeatures features = awaitFeatures(ingested.emailId());

        assertThat(features.featureVersion()).isEqualTo(EmailFeatureExtractor.FEATURE_VERSION);
        assertThat(features.extractedAt()).isNotNull();
        assertThat(features.features().link().urlCount()).isEqualTo(1);
        assertThat(features.features().auth().spf()).isEqualTo("pass");
        assertThat(features.features().timing().hourOfDayUtc()).isEqualTo(14);
        assertThat(features.features().header().hasSubject()).isTrue();
    }

    @Test
    void the_features_are_readable_over_http() throws Exception {
        IngestResult ingested = ingestService.ingest(rawEmail("feature http case"), "api");

        awaitFeatures(ingested.emailId());

        mockMvc.perform(get("/emails/{id}/features", ingested.emailId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emailId").value(ingested.emailId().toString()))
                .andExpect(jsonPath("$.featureVersion").value(EmailFeatureExtractor.FEATURE_VERSION))
                .andExpect(jsonPath("$.features.auth.spf").value("pass"))
                .andExpect(jsonPath("$.features.link.urlCount").value(1));
    }

    @Test
    void two_feature_versions_coexist_for_the_same_email() throws Exception {
        IngestResult ingested = ingestService.ingest(rawEmail("feature coexist case"), "api");
        awaitFeatures(ingested.emailId());

        // Simulate a bumped feature_version landing alongside v1.
        int nextVersion = EmailFeatureExtractor.FEATURE_VERSION + 1;
        repository.save(new EmailFeatures(ingested.emailId(), nextVersion, distinctFeatureSet(), null));

        Optional<EmailFeatures> v1 = repository.find(ingested.emailId(), EmailFeatureExtractor.FEATURE_VERSION);
        Optional<EmailFeatures> v2 = repository.find(ingested.emailId(), nextVersion);

        assertThat(v1).isPresent();
        assertThat(v2).isPresent();
        assertThat(v1.get().features()).isNotEqualTo(v2.get().features());
    }

    /** Polls until the current-version features row for {@code emailId} exists, or fails after a bounded wait. */
    private EmailFeatures awaitFeatures(UUID emailId) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < deadline) {
            Optional<EmailFeatures> found = repository.find(emailId, EmailFeatureExtractor.FEATURE_VERSION);
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(200);
        }
        throw new AssertionError("no email_features row for id " + emailId + " within timeout");
    }

    /**
     * A minimal, unique raw email carrying a URL, auth header, and Date so the
     * extracted features are observable. {@code marker} keeps each test's email
     * distinct (emails dedupe by content hash, and a duplicate is not republished).
     */
    private static byte[] rawEmail(String marker) {
        return ("From: alice@feat-test.example\r\n"
                + "To: bob@feat-test.example\r\n"
                + "Subject: Hello " + marker + "\r\n"
                + "Authentication-Results: mx; spf=pass; dkim=pass; dmarc=pass\r\n"
                + "Date: Wed, 13 Mar 2024 14:30:00 +0000\r\n"
                + "\r\n"
                + "Visit https://feat-test.example/welcome today! (" + marker + ")\r\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    /** A feature set deliberately different from any real extraction, to prove v1≠v2 coexistence. */
    private static FeatureSet distinctFeatureSet() {
        return new FeatureSet(
                new HeaderFeatures(false, 0, 0.0, 0, false, 0, false),
                new LinkFeatures(0, 0, false, false, 0),
                new TextFeatures(0, 0, 0.0, 0, 0.0),
                new TimingFeatures(false, null, null, false),
                new AuthFeatures("unknown", "unknown", "unknown"),
                null);
    }
}
