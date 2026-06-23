package com.antispam.experiment;

/**
 * Thrown when code running inside a read-only experiment scope ({@link ExperimentContext}) attempts
 * to mutate live state — the reputation log/cache, feedback events, or enforced classifications
 * (story 09.03). It signals a broken invariant, not a recoverable condition: an experiment that
 * tried to write production state is a bug in that experiment, and crashing it loudly is the point —
 * far better than silently corrupting the very state the experiment is meant to measure.
 *
 * <p>Unchecked because every live-state mutator guards on it and no caller can meaningfully recover:
 * the correct fix is always to stop writing live state from the experiment path, never to catch this.
 */
public class LiveStateWriteForbiddenException extends RuntimeException {

    private final String table;

    /** @param table the live-state table whose write was blocked (named in the message for triage) */
    public LiveStateWriteForbiddenException(String table) {
        super("blocked write to live-state table '" + table + "' from a read-only experiment context "
                + "(side-effect isolation, story 09.03): shadow, replay, and arena paths must never "
                + "mutate production reputation, feedback, or enforced decisions");
        this.table = table;
    }

    /** The live-state table whose write was rejected. */
    public String table() {
        return table;
    }
}
