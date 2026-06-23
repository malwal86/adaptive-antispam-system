package com.antispam.feedback.gate;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.feedback.FeedbackAction;
import com.antispam.reputation.ReputationSignal;
import org.junit.jupiter.api.Test;

/**
 * The action→polarity mapping (story 07.03): REPORT is negative evidence for the sender, RESCUE and
 * CLICK are positive, and IGNORE asserts nothing so it never reaches state.
 */
class FeedbackSignalTest {

    @Test
    void report_is_a_bad_signal() {
        assertThat(FeedbackSignal.of(FeedbackAction.REPORT)).contains(ReputationSignal.BAD);
    }

    @Test
    void rescue_is_a_good_signal() {
        assertThat(FeedbackSignal.of(FeedbackAction.RESCUE)).contains(ReputationSignal.GOOD);
    }

    @Test
    void click_is_a_good_signal() {
        assertThat(FeedbackSignal.of(FeedbackAction.CLICK)).contains(ReputationSignal.GOOD);
    }

    @Test
    void ignore_carries_no_signal() {
        assertThat(FeedbackSignal.of(FeedbackAction.IGNORE)).isEmpty();
    }
}
