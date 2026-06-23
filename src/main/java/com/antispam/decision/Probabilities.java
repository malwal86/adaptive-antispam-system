package com.antispam.decision;

/** Small helpers for working with probabilities in the decision pipeline. */
public final class Probabilities {

    private Probabilities() {
    }

    /** Clamps {@code v} into the closed unit interval {@code [0, 1]}. */
    public static double clampUnit(double v) {
        if (v < 0.0) {
            return 0.0;
        }
        return v > 1.0 ? 1.0 : v;
    }

    /**
     * Validates that {@code value} is a probability in the closed unit interval
     * {@code [0,1]} (and not NaN), throwing with {@code name} in the message otherwise.
     *
     * @throws IllegalArgumentException if {@code value} is below 0, above 1, or NaN
     */
    public static void requireUnit(String name, double value) {
        if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be in [0,1] but was " + value);
        }
    }
}
