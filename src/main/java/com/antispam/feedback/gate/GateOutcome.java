package com.antispam.feedback.gate;

import java.util.UUID;

/**
 * Summary of gating one feedback run (story 07.03): what the gate did with the run's
 * {@code feedback_events}, at a glance and as the API response body. Proves the gate ran and shows
 * the defence working — how many candidate groups were considered, how many were trusted vs blocked,
 * and how many rows each sink received.
 *
 * @param runId               the gated run
 * @param feedbackEvents      raw events read for the run
 * @param signalEvents        events carrying a polarity (non-{@link com.antispam.feedback.FeedbackAction#IGNORE})
 * @param groupsConsidered    distinct {@link CorroborationKey}s formed from those events
 * @param groupsTrusted       groups that cleared the corroboration gate (moved state)
 * @param groupsBlocked       groups that did not clear it (no state change) — {@code considered - trusted}
 * @param reputationEventsEmitted reputation events appended to the reputation sink (one per trusted group)
 * @param retrainLabelsEmitted retrain labels written to the label sink (one per item in a trusted group)
 */
public record GateOutcome(
        UUID runId,
        int feedbackEvents,
        int signalEvents,
        int groupsConsidered,
        int groupsTrusted,
        int groupsBlocked,
        int reputationEventsEmitted,
        int retrainLabelsEmitted) {
}
