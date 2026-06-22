package com.antispam.decision;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.ReasonCode.Origin;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The reason-code vocabulary and the rule that decides which codes the LLM may assert (story
 * 05.03). The enum is the single source of truth; {@link ReasonCode#availableToLlm()} partitions
 * it into facts a deterministic check establishes (which the model cannot verify) versus content
 * judgments the model is positioned to make.
 */
class ReasonCodeTest {

    @Test
    void llm_selectable_is_exactly_the_llm_origin_codes() {
        List<ReasonCode> selectable = ReasonCode.llmSelectable();

        assertThat(selectable).isNotEmpty();
        assertThat(selectable).allMatch(c -> c.origin() == Origin.LLM);
        assertThat(selectable).contains(
                ReasonCode.SUSPICIOUS_LINK,
                ReasonCode.CREDENTIAL_PHISHING,
                ReasonCode.PRIZE_OR_LOTTERY_BAIT,
                ReasonCode.SENDER_REPUTATION_RISK,
                ReasonCode.BENIGN_CONTENT);
    }

    @Test
    void hard_rule_and_detector_facts_are_not_offered_to_the_llm() {
        // A denylist hit and a detected burst are established before the LLM is consulted and
        // cannot be verified by it — so the model is never offered them, even though they are
        // valid enum members the contract still accepts.
        assertThat(ReasonCode.KNOWN_BAD_URL.availableToLlm()).isFalse();
        assertThat(ReasonCode.MALFORMED_AUTH_BRAND_SPOOF.availableToLlm()).isFalse();
        assertThat(ReasonCode.BURST_OVERRIDE.availableToLlm()).isFalse();
        assertThat(ReasonCode.llmSelectable())
                .doesNotContain(ReasonCode.KNOWN_BAD_URL, ReasonCode.BURST_OVERRIDE);
    }

    @ParameterizedTest
    @EnumSource(ReasonCode.class)
    void availability_tracks_origin_for_every_code(ReasonCode code) {
        assertThat(code.availableToLlm()).isEqualTo(code.origin() == Origin.LLM);
    }
}
