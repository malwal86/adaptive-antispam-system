package com.antispam.reputation;

/**
 * A sender's reputation as a Beta distribution over its spam/abuse base rate
 * (PRD §Subsystem 3). It is built from weighted {@code good}/{@code bad} counts
 * plus an {@code (alpha, beta)} prior of pseudo-counts:
 *
 * <pre>{@code  reputation = (good + alpha) / (good + bad + alpha + beta) }</pre>
 *
 * <p>The point of modelling reputation as a distribution rather than a single
 * number is that it carries its own uncertainty: a brand-new sender (no evidence)
 * has the prior's wide {@link #variance() variance}, and that variance shrinks as
 * good/bad evidence accrues. Downstream that width is the routing signal — a wide
 * Beta widens the Bayesian-fusion band (Epic 04) and routes the sender to the LLM
 * (Epic 05) — so reputation gets "uncertainty for free".
 *
 * <p>This is a pure value object: {@link #mean()} and {@link #variance()} are
 * functions of the four fields and nothing else (no clock, no I/O), which is what
 * lets reputation be recomputed from the append-only event log and audited
 * (story 03.01 AC 4).
 *
 * @param good  weighted count of good signals; must be {@code >= 0}
 * @param bad   weighted count of bad signals; must be {@code >= 0}
 * @param alpha prior pseudo-count of good observations; must be {@code > 0}
 * @param beta  prior pseudo-count of bad observations; must be {@code > 0}
 */
public record BetaReputation(double good, double bad, double alpha, double beta) {

    public BetaReputation {
        if (alpha <= 0 || beta <= 0) {
            throw new IllegalArgumentException(
                    "Beta prior must be positive: alpha=" + alpha + " beta=" + beta);
        }
        if (good < 0 || bad < 0) {
            throw new IllegalArgumentException(
                    "reputation counts must be non-negative: good=" + good + " bad=" + bad);
        }
    }

    /**
     * The posterior mean spam-trust estimate in {@code (0, 1)} — the Beta mean
     * {@code a / (a + b)} with {@code a = good + alpha}, {@code b = bad + beta}.
     * Higher means more good-signal weight relative to bad.
     */
    public double mean() {
        double a = good + alpha;
        double b = bad + beta;
        return a / (a + b);
    }

    /**
     * The posterior variance — how uncertain {@link #mean()} is. Largest for a new
     * sender (the prior) and monotonically smaller as evidence grows, so it is the
     * natural "should we escalate this sender?" signal.
     */
    public double variance() {
        double a = good + alpha;
        double b = bad + beta;
        double sum = a + b;
        return (a * b) / (sum * sum * (sum + 1));
    }

    /**
     * The observed evidence count {@code good + bad}, excluding the prior
     * pseudo-counts. Zero for a sender with no recorded events.
     */
    public double count() {
        return good + bad;
    }
}
