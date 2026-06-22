package com.antispam.decision.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.decision.Classification;
import com.antispam.decision.ClassificationRepository;
import com.antispam.decision.Decision;
import com.antispam.decision.calibration.ModelCalibrationService;
import com.antispam.decision.model.OnnxModel;
import com.antispam.eval.BootstrapEvalSplitService;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestResult;
import com.antispam.ingest.IngestService;
import com.antispam.seed.GroundTruthLabel;
import com.antispam.seed.GroundTruthLabelRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * The tiering slice end-to-end against a real Postgres (story 04.05): the seeded active
 * policy is real (the decision path looks it up and stamps {@code policy_version} on the
 * persisted row), and switching the active policy to one with different thresholds changes
 * the tier a fixed email earns — proving the regime is data, not code.
 *
 * <p>Calibration is run first (ceiling relaxed) so the model score is fused into a posterior
 * the policy can tier; the fusion numerics are covered in {@code FusionIntegrationTest}.
 */
@TestPropertySource(properties = {
        "antispam.calibration.min-samples-per-side=1",
        "antispam.calibration.max-ece=1.0",
        "antispam.calibration.bins=5",
})
class PolicyDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String DOMAIN_SUFFIX = ".policy.test";

    @Autowired
    private IngestService ingestService;

    @Autowired
    private GroundTruthLabelRepository labels;

    @Autowired
    private BootstrapEvalSplitService splitService;

    @Autowired
    private ModelCalibrationService calibrationService;

    @Autowired
    private com.antispam.decision.DecisionService decisionService;

    @Autowired
    private ClassificationRepository classifications;

    @Autowired
    private PolicyRepository policies;

    @AfterEach
    void restoreSeedPolicy() {
        // The policies table is shared across the whole integration suite (one Postgres
        // container); restore the seeded regime so a policy switch here cannot leak into
        // another test class's decisions.
        policies.activate("bootstrap-v1");
    }

    @Test
    void seeds_an_active_policy_and_stamps_its_version_on_every_decision() {
        // The V15 migration seeds the bootstrap regime, and exactly one policy is active.
        assertThat(policies.findByVersion("bootstrap-v1")).isPresent();
        Policy active = policies.findActive().orElseThrow();

        Email email = ingest("anysender" + DOMAIN_SUFFIX, hamBody());
        Classification decided = decisionService.decide(email);

        // Whatever tier it earns, the decision is stamped with the regime that made it.
        assertThat(decided.policyVersion()).isEqualTo(active.version());
        assertThat(classifications.findByEmailId(email.id()))
                .last()
                .satisfies(c -> assertThat(c.policyVersion()).isEqualTo(active.version()));
    }

    @Test
    void switching_the_active_policy_changes_the_tier_for_the_same_email() {
        // Calibrate so the model score is fused into a posterior the thresholds act on.
        for (int i = 0; i < 8; i++) {
            ingestLabeled("ham" + i, 2001 + i, GroundTruthLabel.HAM, hamBody());
            ingestLabeled("bad" + i, 2001 + i, GroundTruthLabel.SPAM, spamBody());
        }
        splitService.rebuild();
        calibrationService.calibrate();

        Email email = ingest("subject" + DOMAIN_SUFFIX, hamBody());

        // Read the email's actual fused posterior, then bracket it with two regimes so the
        // tier difference is forced regardless of the exact score: one whose every threshold
        // sits above the posterior (⇒ allow), one whose every threshold sits below it (⇒ block).
        Classification first = decisionService.decide(email);
        assertThat(first.fused()).isNotNull();
        double p = first.fused().posterior();
        double above = Math.min(1.0, p + 0.01);
        double below = Math.max(0.0, p - 0.01);

        policies.save(new Policy("allow-all-v2", false, above, above, above, 0.40, OnnxModel.MODEL_VERSION, Instant.EPOCH));
        policies.activate("allow-all-v2");
        Decision underLenient = decisionService.decide(email).decision();

        policies.save(new Policy("block-all-v2", false, below, below, below, 0.40, OnnxModel.MODEL_VERSION, Instant.EPOCH));
        policies.activate("block-all-v2");
        Decision underStrict = decisionService.decide(email).decision();

        // Same email, same posterior, different active policy ⇒ different tier. Policy is real.
        assertThat(underLenient).isEqualTo(Decision.ALLOW);
        assertThat(underStrict).isEqualTo(Decision.BLOCK);
        assertThat(underStrict.compareTo(underLenient)).isGreaterThan(0);
    }

    private Email ingest(String fromDomain, String body) {
        String raw = "From: sender@" + fromDomain + "\n"
                + "Date: Tue, 1 Jan 2026 12:00:00 +0000\n"
                + "Subject: a subject line\n"
                + "Authentication-Results: mx.test; spf=pass; dkim=pass; dmarc=pass\n\n"
                + body + " " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    private void ingestLabeled(String domain, int year, GroundTruthLabel label, String body) {
        String raw = "From: sender@" + domain + DOMAIN_SUFFIX + "\n"
                + "Date: Tue, 1 Jan " + year + " 00:00:00 +0000\n"
                + "Subject: calibration " + domain + " " + year + "\n"
                + "Authentication-Results: mx.test; spf=pass\n\n"
                + body + " " + UUID.randomUUID();
        IngestResult result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "seed");
        labels.saveIfAbsent(result.emailId(), label, "t-policy-" + domain);
    }

    private static String hamBody() {
        return "Hi, confirming our lunch meeting tomorrow at noon. See you then.";
    }

    private static String spamBody() {
        return "WIN A FREE PRIZE NOW!!! CLICK http://example.com/claim to CLAIM your REWARD!!! "
                + "ACT NOW limited time OFFER buy CHEAP meds http://promo.example.com/deal";
    }
}
