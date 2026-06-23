package com.antispam.feedback;

import java.util.Map;
import java.util.UUID;

/**
 * Summary of one feedback-simulation run (story 07.02): its id, how many events it produced, and
 * the breakdown by action. The id groups the run's {@code feedback_events} rows so the run can be
 * read back or compared as a unit.
 *
 * @param runId           the run's id (stamped on every event it wrote)
 * @param eventCount      number of events produced (one per decided email)
 * @param countsByAction  events per action — the at-a-glance shape of the run
 */
public record FeedbackRun(UUID runId, int eventCount, Map<FeedbackAction, Long> countsByAction) {

    public FeedbackRun {
        countsByAction = Map.copyOf(countsByAction);
    }
}
