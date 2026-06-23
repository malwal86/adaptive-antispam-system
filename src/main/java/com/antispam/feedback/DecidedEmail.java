package com.antispam.feedback;

import com.antispam.decision.Decision;
import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * One item of the decision stream the feedback simulator acts on (story 07.02): an email, the
 * verdict the user was shown, and the email's ground truth. It pairs <em>what the filter did</em>
 * with <em>what the mail actually is</em> — the two inputs (besides persona bias) the
 * {@link ActionSampler} conditions an action on.
 *
 * @param emailId       the decided email
 * @param decisionShown the verdict the user saw (drives the action space)
 * @param groundTruth   the email's true class (drives how likely each action is)
 */
public record DecidedEmail(UUID emailId, Decision decisionShown, GroundTruthLabel groundTruth) {

    public DecidedEmail {
        if (emailId == null || decisionShown == null || groundTruth == null) {
            throw new IllegalArgumentException("emailId, decisionShown and groundTruth are required");
        }
    }
}
