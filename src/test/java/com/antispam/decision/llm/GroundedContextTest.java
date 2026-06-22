package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.routing.RoutingReason;
import com.antispam.features.EmailFeatures;
import com.antispam.features.FeatureSet;
import com.antispam.features.FeatureSet.AuthFeatures;
import com.antispam.features.FeatureSet.HeaderFeatures;
import com.antispam.features.FeatureSet.LinkFeatures;
import com.antispam.features.FeatureSet.TextFeatures;
import com.antispam.features.FeatureSet.TimingFeatures;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The grounded context (story 05.03): the trusted, machine-extracted basis the LLM reasons over.
 * These tests pin the three properties the story turns on — it carries the extracted features,
 * reputation summary, and escalation reason; it excludes the raw email and any system
 * instructions; and it renders reproducibly for a given email + model so a decision is auditable.
 */
class GroundedContextTest {

    private static final UUID EMAIL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static EmailFeatures features() {
        FeatureSet set = new FeatureSet(
                new HeaderFeatures(true, 18, 0.40, 3, true, 1, true),
                new LinkFeatures(2, 1, true, false, 73),
                new TextFeatures(240, 41, 0.22, 5, 4.1),
                new TimingFeatures(true, 3, 2, false),
                new AuthFeatures("pass", "fail", "fail"),
                null);
        return new EmailFeatures(EMAIL_ID, 1, set, null);
    }

    private static SenderReputationSummary reputation() {
        return new SenderReputationSummary(0.31, 4.0, 0.18, false);
    }

    @Test
    void renders_extracted_features_the_reputation_summary_and_why_escalated() {
        GroundedContext context = new GroundedContext(
                features(), reputation(), List.of(RoutingReason.NEAR_TIER_BOUNDARY));

        String rendered = context.render();

        assertThat(rendered)
                .contains("Why escalated to you: NEAR_TIER_BOUNDARY")
                .contains("Feature version: 1")
                .contains("url_count=2")
                .contains("has_ip_url=true")
                .contains("uppercase_ratio=0.22")
                .contains("spf=pass dkim=fail dmarc=fail")
                .contains("Sender reputation: trust_mean=0.310 evidence_count=4.0 uncertainty=0.180 "
                        + "dmarc_aligned=false");
    }

    @Test
    void excludes_raw_email_text_and_system_instructions() {
        String rendered = new GroundedContext(features(), reputation(), List.of(RoutingReason.LOW_MODEL_CONFIDENCE))
                .render();

        // It is data, not instructions: none of the prompt's directive text leaks into the grounding.
        assertThat(rendered)
                .doesNotContain("Respond with")
                .doesNotContain("classifier")
                .doesNotContain("JSON")
                .doesNotContain("BEGIN EMAIL");
    }

    @Test
    void is_reproducible_for_the_same_inputs() {
        List<RoutingReason> reasons = List.of(RoutingReason.NEW_SENDER_UNCERTAINTY);

        String first = new GroundedContext(features(), reputation(), reasons).render();
        String second = new GroundedContext(features(), reputation(), reasons).render();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void renders_a_sentinel_when_no_escalation_reason_is_present() {
        String rendered = new GroundedContext(features(), reputation(), List.of()).render();

        assertThat(rendered).contains("Why escalated to you: (unspecified)");
    }

    @Test
    void rejects_missing_features_or_reputation() {
        assertThatThrownBy(() -> new GroundedContext(null, reputation(), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GroundedContext(features(), null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
