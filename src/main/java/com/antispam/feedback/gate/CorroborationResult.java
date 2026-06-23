package com.antispam.feedback.gate;

/**
 * The verdict of corroborating one {@link CorroborationKey}'s feedback (story 07.03): how many
 * independent personas reported it, their summed weight, and whether that clears the trust gate.
 * Self-describing so the gate decision is auditable from the result alone.
 *
 * @param corroborators   number of <em>distinct</em> personas reporting the key — the unit of
 *                        independence (two actions from one persona corroborate nothing)
 * @param aggregateWeight sum of the per-item weights ({@code trust × confidence}); the down-weighted
 *                        sum a malicious bomber alone cannot inflate
 * @param trusted         whether the group cleared both thresholds (enough distinct reporters and
 *                        enough aggregate weight) and may move state
 */
public record CorroborationResult(int corroborators, double aggregateWeight, boolean trusted) {

    public CorroborationResult {
        if (corroborators < 0) {
            throw new IllegalArgumentException("corroborators must be >= 0 but was " + corroborators);
        }
        if (aggregateWeight < 0 || Double.isNaN(aggregateWeight)) {
            throw new IllegalArgumentException("aggregateWeight must be >= 0 but was " + aggregateWeight);
        }
    }
}
