package com.antispam.feedback;

import static org.assertj.core.api.Assertions.assertThat;

import com.antispam.decision.Decision;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The action space is conditioned on what the filter actually did (story 07.02, AC 1/2):
 * you only rescue a withheld mail and only click/report a delivered one — no impossible
 * action is ever offered.
 */
class FeedbackActionTest {

    @ParameterizedTest
    @EnumSource(value = Decision.class, names = {"ALLOW", "WARN"})
    void delivered_mail_can_be_clicked_reported_or_ignored_but_not_rescued(Decision delivered) {
        assertThat(FeedbackAction.spaceFor(delivered))
                .containsExactly(FeedbackAction.CLICK, FeedbackAction.REPORT, FeedbackAction.IGNORE)
                .doesNotContain(FeedbackAction.RESCUE);
    }

    @ParameterizedTest
    @EnumSource(value = Decision.class, names = {"QUARANTINE", "BLOCK"})
    void withheld_mail_can_be_rescued_or_ignored_but_not_clicked_or_reported(Decision withheld) {
        assertThat(FeedbackAction.spaceFor(withheld))
                .containsExactly(FeedbackAction.RESCUE, FeedbackAction.IGNORE)
                .doesNotContain(FeedbackAction.CLICK, FeedbackAction.REPORT);
    }
}
