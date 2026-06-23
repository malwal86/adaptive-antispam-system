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
}
