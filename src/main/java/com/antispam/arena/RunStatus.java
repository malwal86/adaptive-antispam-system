package com.antispam.arena;

import java.util.Locale;

/**
 * The lifecycle of an {@link AdversarialRun} (story 08.02). A run is {@link #RUNNING} while the
 * bounded loop is live and reaches exactly one terminal state:
 *
 * <ul>
 *   <li>{@link #COMPLETED} — the loop terminated cleanly within its bounds: the generation cap was
 *       reached, the target bypass rate was met, or a generation produced nothing that bypassed so
 *       there was no gap left to attack.</li>
 *   <li>{@link #BUDGET_EXHAUSTED} — the hard spend ceiling stopped the loop before the cap; the
 *       generations completed so far are still recorded (partial results, AC 5).</li>
 *   <li>{@link #FAILED} — an infrastructure error aborted the run (e.g. the attacker model was
 *       unreachable mid-loop), so the run is finalized rather than left dangling in {@code running}.</li>
 * </ul>
 *
 * <p>Stored as a stable lowercase token like {@link com.antispam.seed.GroundTruthLabel} and
 * {@link MutationStrategy}, matching how the rest of the schema records small enums.
 */
public enum RunStatus {

    RUNNING,
    COMPLETED,
    BUDGET_EXHAUSTED,
    FAILED;

    /** The lowercase token stored in {@code adversarial_runs.status}. */
    public String dbValue() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Inverse of {@link #dbValue()}, for reading a stored status back.
     *
     * @throws IllegalArgumentException if {@code value} is not a recognized status token
     */
    public static RunStatus fromDbValue(String value) {
        return valueOf(value.toUpperCase(Locale.ROOT));
    }
}
