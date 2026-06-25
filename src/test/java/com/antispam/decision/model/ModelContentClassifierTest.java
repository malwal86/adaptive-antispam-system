package com.antispam.decision.model;

import static org.assertj.core.api.Assertions.assertThat;

import static org.mockito.Mockito.when;

import com.antispam.decision.Decision;
import com.antispam.decision.DecisionOutcome;
import com.antispam.decision.RouteUsed;
import com.antispam.decision.calibration.ActiveCalibrator;
import com.antispam.decision.policy.PolicyRepository;
import com.antispam.features.EmailFeatureExtractor;
import com.antispam.ingest.Email;
import com.antispam.ingest.ParsedEmail;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;

/**
 * The model path turns an email into a scored {@link DecisionOutcome}. Wired with
 * the real extractor and ONNX model (both cheap and deterministic — no mocks), so
 * this exercises the full extract → vectorize → score chain the bean runs in
 * production, just without Spring or a database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ModelContentClassifierTest {

    private OnnxModel model;
    private ModelRegistry registry;
    private ActiveCalibrator calibrator;
    private ModelContentClassifier classifier;

    @BeforeAll
    void setUp() {
        model = new OnnxModel();
        registry = new ModelRegistry(model, new ClasspathModelArtifactStore());
        // No active policy here, so ServedModel falls back to the bootstrap version — the same model
        // and version this test asserted before 10.04 made the serving path policy-driven.
        PolicyRepository policies = Mockito.mock(PolicyRepository.class);
        when(policies.findActive()).thenReturn(Optional.empty());
        ServedModel servedModel = new ServedModel(registry, policies);
        calibrator = new ActiveCalibrator();
        classifier = new ModelContentClassifier(new EmailFeatureExtractor(), servedModel, calibrator);
    }

    @AfterAll
    void close() throws Exception {
        registry.close();
        model.close();
    }

    private static Email email(String raw, String authResults) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ParsedEmail md = new ParsedEmail(
                "sender@example.com", "example.com", "you@example.com",
                "subject line", Instant.parse("2026-06-05T14:00:00Z"), authResults);
        return new Email(UUID.randomUUID(), new byte[]{1}, bytes, md, "test", Instant.now());
    }

    @Test
    void classifies_on_the_model_route_with_scores_attached() {
        DecisionOutcome outcome = classifier.classify(email("Hello, lunch tomorrow?", "spf=pass"));

        assertThat(outcome.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(outcome.scores()).isNotNull();
        assertThat(outcome.scores().modelVersion()).isEqualTo(OnnxModel.MODEL_VERSION);
        assertThat(outcome.scores().spamScore()).isBetween(0.0, 1.0);
        assertThat(outcome.scores().phishingScore()).isBetween(0.0, 1.0);
        assertThat(outcome.latencyMs()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void serves_the_raw_abuse_score_as_confidence_until_a_calibration_is_installed() {
        // The default active calibrator is the identity, so the served confidence is the
        // raw P(abuse) = spam + phish — the model is never in an "uncalibrated" null state.
        DecisionOutcome outcome = classifier.classify(email("Hello, lunch tomorrow?", "spf=pass"));

        var scores = outcome.scores();
        assertThat(scores.calibratedConfidence())
                .isCloseTo(scores.rawMalicious(), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void serves_the_calibrated_confidence_once_a_calibration_is_installed() {
        // Install a calibrator that maps every raw score to a fixed 0.42; the served
        // confidence must be that calibrated value, not the raw model output (AC 2).
        calibrator.install(raw -> 0.42);
        try {
            DecisionOutcome outcome = classifier.classify(email("Hello, lunch tomorrow?", "spf=pass"));
            assertThat(outcome.scores().calibratedConfidence()).isEqualTo(0.42);
            // The raw scores are untouched — calibration corrects the confidence, not them.
            assertThat(outcome.scores().spamScore()).isBetween(0.0, 1.0);
        } finally {
            calibrator.install(raw -> raw);
        }
    }

    @Test
    void returns_a_provisional_allow_until_the_tier_policy_lands() {
        // Story scope: the model path records the score but does not yet turn it into
        // a tier (that is 04.04 fusion + 04.05 policy). Provisional ALLOW is expected.
        DecisionOutcome outcome = classifier.classify(email("Anything at all", "spf=pass"));
        assertThat(outcome.decision()).isEqualTo(Decision.ALLOW);
        assertThat(outcome.reasonCodes()).isEmpty();
    }
}
