package com.antispam.feedback;

import com.antispam.decision.Decision;
import com.antispam.seed.GroundTruthLabel;
import java.util.UUID;

/**
 * A persisted feedback event (story 07.02): one persona's sampled action on one decided email, with
 * the conditioning (verdict shown, ground truth) recorded alongside so a row is self-describing and
 * auditable. One row of {@code feedback_events}. The weighting/corroboration gate (07.03) reads
 * these before any of them is allowed to move reputation or a retrain label.
 *
 * @param id            canonical id of this event
 * @param emailId       the decided email
 * @param personaId     the persona that acted (07.01)
 * @param runId         the simulation run that produced it
 * @param action        the sampled action
 * @param confidence    the sampler's confidence in the action, in {@code [0,1]}
 * @param delaySeconds  seconds the persona waited before acting
 * @param decisionShown the verdict the user saw
 * @param groundTruth   the email's true class
 * @param source        human-readable producer token (the persona name)
 */
public record FeedbackEvent(
        UUID id,
        UUID emailId,
        UUID personaId,
        UUID runId,
        FeedbackAction action,
        double confidence,
        long delaySeconds,
        Decision decisionShown,
        GroundTruthLabel groundTruth,
        String source) {
}
