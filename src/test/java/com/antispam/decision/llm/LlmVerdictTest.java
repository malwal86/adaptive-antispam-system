package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.antispam.decision.ReasonCode;
import com.antispam.decision.llm.LlmVerdict.Verdict;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The structured-output contract (story 05.02): an {@link LlmVerdict} is a strictly-typed
 * record so a malformed or manipulated LLM response can never reach the decision path. These
 * tests pin the record's own invariants — every field present and in range — and the
 * snake_case JSON field names the model is asked to produce.
 */
class LlmVerdictTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void holds_all_fields_when_well_formed() {
        LlmVerdict verdict = new LlmVerdict(
                Verdict.SPAM, 0.91, 0.04, List.of(ReasonCode.KNOWN_BAD_URL), "Looks like a payment lure.");

        assertThat(verdict.verdict()).isEqualTo(Verdict.SPAM);
        assertThat(verdict.spamProb()).isEqualTo(0.91);
        assertThat(verdict.phishingProb()).isEqualTo(0.04);
        assertThat(verdict.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
        assertThat(verdict.explanationShort()).isEqualTo("Looks like a payment lure.");
    }

    @Test
    void defensively_copies_the_reason_codes() {
        List<ReasonCode> codes = new ArrayList<>(List.of(ReasonCode.KNOWN_BAD_URL));
        LlmVerdict verdict = new LlmVerdict(Verdict.SPAM, 0.9, 0.1, codes, "x");

        codes.add(ReasonCode.BURST_OVERRIDE);

        assertThat(verdict.reasonCodes()).containsExactly(ReasonCode.KNOWN_BAD_URL);
    }

    @Test
    void allows_an_empty_reason_code_list() {
        LlmVerdict verdict = new LlmVerdict(Verdict.LEGITIMATE, 0.02, 0.01, List.of(), "Benign newsletter.");

        assertThat(verdict.reasonCodes()).isEmpty();
    }

    @Test
    void rejects_a_spam_probability_outside_the_unit_interval() {
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 1.01, 0.0, List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, -0.01, 0.0, List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_phishing_probability_outside_the_unit_interval() {
        assertThatThrownBy(() -> new LlmVerdict(Verdict.PHISHING, 0.0, 1.5, List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_a_null_verdict() {
        assertThatThrownBy(() -> new LlmVerdict(null, 0.5, 0.5, List.of(), "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_null_reason_codes() {
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 0.5, 0.5, null, "x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_null_reason_code_element() {
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 0.5, 0.5, Arrays.asList((ReasonCode) null), "x"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejects_a_blank_explanation() {
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 0.5, 0.5, List.of(), "   "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 0.5, 0.5, List.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_an_explanation_longer_than_the_bound() {
        String tooLong = "x".repeat(LlmVerdict.MAX_EXPLANATION_LENGTH + 1);

        assertThatThrownBy(() -> new LlmVerdict(Verdict.SPAM, 0.5, 0.5, List.of(), tooLong))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deserializes_from_the_snake_case_field_names_the_model_returns() throws Exception {
        String json =
                """
                {
                  "verdict": "PHISHING",
                  "spam_prob": 0.12,
                  "phishing_prob": 0.88,
                  "reason_codes": ["MALFORMED_AUTH_BRAND_SPOOF"],
                  "explanation_short": "Brand spoof with misaligned auth."
                }
                """;

        LlmVerdict verdict = MAPPER.readValue(json, LlmVerdict.class);

        assertThat(verdict.verdict()).isEqualTo(Verdict.PHISHING);
        assertThat(verdict.spamProb()).isEqualTo(0.12);
        assertThat(verdict.phishingProb()).isEqualTo(0.88);
        assertThat(verdict.reasonCodes()).containsExactly(ReasonCode.MALFORMED_AUTH_BRAND_SPOOF);
        assertThat(verdict.explanationShort()).isEqualTo("Brand spoof with misaligned auth.");
    }
}
