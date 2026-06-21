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
}
