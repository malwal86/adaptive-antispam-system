package com.antispam.analyze;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import com.antispam.decision.ReasonCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The explanation is grounded: every clause traces to a reason code that fired,
 * the verdict word matches the tier, and a plain allow reads as a non-alarming
 * placeholder rather than a confident "clean" claim.
 */
class AnalysisExplainerTest {

    @Test
    void block_on_known_bad_url_explains_the_link() {
        String explanation = AnalysisExplainer.explain(Decision.BLOCK, List.of(ReasonCode.KNOWN_BAD_URL));

        assertThat(explanation)
                .startsWith("Blocked:")
                .contains("known-malicious host")
                .endsWith(".");
    }

    @Test
    void quarantine_on_brand_spoof_explains_the_failed_auth() {
        String explanation =
                AnalysisExplainer.explain(Decision.QUARANTINE, List.of(ReasonCode.MALFORMED_AUTH_BRAND_SPOOF));

        assertThat(explanation)
                .startsWith("Quarantined:")
                .contains("impersonates a high-value brand")
                .contains("DMARC");
    }

    @Test
    void multiple_codes_are_all_named() {
        String explanation = AnalysisExplainer.explain(
                Decision.BLOCK,
                List.of(ReasonCode.KNOWN_BAD_URL, ReasonCode.MALFORMED_AUTH_BRAND_SPOOF));

        assertThat(explanation)
                .contains("known-malicious host")
                .contains("impersonates a high-value brand")
                .contains(";");
    }

    @Test
    void plain_allow_reads_as_a_policy_allow() {
        String explanation = AnalysisExplainer.explain(Decision.ALLOW, List.of());

        // A no-reason allow is the model scoring it benign and the active policy allowing
        // it (story 04.05) — grounded, and not a confident severe verdict.
        assertThat(explanation)
                .contains("the model scored it as benign")
                .contains("the active policy")
                .doesNotContain("Blocked");
    }

    @Test
    void burst_override_reads_as_a_burst_escalation() {
        String explanation = AnalysisExplainer.explain(Decision.QUARANTINE, List.of(ReasonCode.BURST_OVERRIDE));

        assertThat(explanation)
                .contains("Quarantined")
                .contains("detected sending burst");
    }
}
