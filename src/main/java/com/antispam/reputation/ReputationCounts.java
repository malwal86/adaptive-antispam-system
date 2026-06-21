package com.antispam.reputation;

/**
 * The weighted good/bad totals for one sender, summed from the
 * {@code reputation_events} log (story 03.01). It is the bridge between the
 * repository (which does the SQL aggregation) and {@link BetaReputation} (which
 * applies the prior): the repository knows nothing about {@code alpha}/{@code beta},
 * and the math knows nothing about SQL.
 *
 * @param good weighted sum of {@code GOOD} signals ({@code 0} when none)
 * @param bad  weighted sum of {@code BAD} signals ({@code 0} when none)
 */
public record ReputationCounts(double good, double bad) {

    /**
     * These counts with both buckets scaled by {@code factor} — used to age a folded
     * cache snapshot forward to the read instant (story 03.04). Because exponential
     * decay composes multiplicatively (story 03.02), scaling the aggregate by one
     * factor equals decaying every underlying event by that factor.
     */
    public ReputationCounts scaledBy(double factor) {
        return new ReputationCounts(good * factor, bad * factor);
    }
}
