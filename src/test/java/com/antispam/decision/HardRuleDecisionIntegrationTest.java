package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.antispam.AbstractPostgresIntegrationTest;
import com.antispam.ingest.Email;
import com.antispam.ingest.IngestService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * End-to-end of the 01.04 decision pipeline against a real Postgres: a hard-rule
 * hit produces a persisted decision with the right reason code <em>and</em> the
 * model path is never touched (the cost-saving short-circuit), while mail that
 * matches no rule falls through to the model seam.
 *
 * <p>The model seam is replaced with a Mockito spy so "the model was skipped" is
 * an assertion ({@code verifyNoInteractions}), exactly the success metric the
 * story calls for.
 */
class HardRuleDecisionIntegrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private DecisionService decisionService;

    @Autowired
    private IngestService ingestService;

    @Autowired
    private ClassificationRepository classifications;

    @MockitoBean
    private ContentClassifier contentClassifier;

    private Email ingest(String raw) {
        var result = ingestService.ingest(raw.getBytes(StandardCharsets.UTF_8), "test");
        return ingestService.findById(result.emailId()).orElseThrow();
    }

    @Test
    void known_bad_url_is_blocked_by_a_hard_rule_with_the_model_skipped_and_the_decision_persisted() {
        Email email = ingest("""
                From: deals@promo.example
                Subject: Act now

                Verify your prize at http://malware.example/login today.
                """);

        Classification decision = decisionService.decide(email);

        assertThat(decision.decision()).isEqualTo(Decision.BLOCK);
        assertThat(decision.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(decision.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
        verifyNoInteractions(contentClassifier);

        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .satisfies(stored -> {
                    assertThat(stored.route()).isEqualTo(RouteUsed.HARD_RULE);
                    assertThat(stored.decision()).isEqualTo(Decision.BLOCK);
                    assertThat(stored.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
                    assertThat(stored.latencyMs()).isGreaterThanOrEqualTo(0);
                    assertThat(stored.createdAt()).isNotNull();
                });
    }

    @Test
    void brand_spoof_with_failing_dmarc_is_quarantined_with_the_model_skipped() {
        Email email = ingest("""
                From: security@paypal.account-verify.com
                Subject: Your account is limited
                Authentication-Results: mx.test; spf=fail; dmarc=fail

                Confirm your details to restore access.
                """);

        Classification decision = decisionService.decide(email);

        assertThat(decision.decision()).isEqualTo(Decision.QUARANTINE);
        assertThat(decision.route()).isEqualTo(RouteUsed.HARD_RULE);
        assertThat(decision.reasonCodes()).containsExactly(ReasonCode.MALFORMED_AUTH_BRAND_SPOOF);
        verifyNoInteractions(contentClassifier);
    }

    @Test
    void mail_matching_no_hard_rule_falls_through_to_the_model_path() {
        when(contentClassifier.classify(any()))
                .thenReturn(new DecisionOutcome(Decision.ALLOW, List.of(), RouteUsed.MODEL, 1L));

        Email email = ingest("""
                From: newsletter@good.example
                Subject: Weekly update

                Nothing suspicious here. Read more at https://good.example/news
                """);

        Classification decision = decisionService.decide(email);

        assertThat(decision.route()).isEqualTo(RouteUsed.MODEL);
        assertThat(decision.decision()).isEqualTo(Decision.ALLOW);
        verify(contentClassifier).classify(any());

        assertThat(classifications.findByEmailId(email.id()))
                .singleElement()
                .extracting(Classification::route)
                .isEqualTo(RouteUsed.MODEL);
    }
}
