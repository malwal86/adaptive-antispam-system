package com.antispam.feedback.web;

import com.antispam.feedback.FeedbackAction;
import com.antispam.feedback.FeedbackRun;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * The result of a feedback-simulation run for the API (story 07.02): the run id, the event count,
 * and the per-action breakdown. The id lets a caller read the run's {@code feedback_events} back.
 *
 * @param runId          the run's id
 * @param eventCount     events produced (one per decided email)
 * @param countsByAction events per action name, sorted for a stable response
 */
public record FeedbackRunResponse(UUID runId, int eventCount, Map<String, Long> countsByAction) {

    public static FeedbackRunResponse from(FeedbackRun run) {
        Map<String, Long> counts = run.countsByAction().entrySet().stream()
                .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue,
                        (a, b) -> a, TreeMap::new));
        return new FeedbackRunResponse(run.runId(), run.eventCount(), counts);
    }

    /** Convenience for callers/tests that want a zero count for an absent action. */
    public long count(FeedbackAction action) {
        return countsByAction().getOrDefault(action.name(), 0L);
    }
}
