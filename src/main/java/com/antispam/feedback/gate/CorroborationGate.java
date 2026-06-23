package com.antispam.feedback.gate;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The corroboration half of the feedback gate (story 07.03): aggregates the weighted feedback for
 * one {@link CorroborationKey} and decides whether it is trusted enough to move state. Pure and
 * database-free so the threshold policy is auditable and unit-testable (test plan: "corroboration
 * aggregation + threshold").
 *
 * <p><b>Why both a count and a weight threshold.</b> The count of <em>distinct</em> personas defends
 * against report-bombing: one persona — however high its bias or however many times it acts — can
 * never clear {@code minCorroborators}, so a single report cannot by itself move reputation or a
 * label (AC 1). The aggregate-weight threshold defends against low-trust corroboration: a handful of
 * malicious bombers (each down-weighted to {@code maliciousTrust} by {@link FeedbackWeighting}) sum
 * to little and stay below {@code minWeight}, while genuinely corroborated good-faith reports clear
 * it and produce a measurable, bounded state update (AC 2). A group must clear <em>both</em>.
 */
public final class CorroborationGate {

    private CorroborationGate() {
    }

    /**
     * Aggregates a group's items and decides trust.
     *
     * @param group           the weighted feedback sharing one {@link CorroborationKey}; must be
     *                        non-empty (the caller only forms groups from at least one item)
     * @param minCorroborators minimum distinct personas required ({@code >= 1})
     * @param minWeight       minimum aggregate weight required ({@code >= 0})
     * @return the corroborator count, summed weight, and whether both thresholds are met
     */
    public static CorroborationResult evaluate(
            Collection<WeightedFeedback> group, int minCorroborators, double minWeight) {
        if (group == null || group.isEmpty()) {
            throw new IllegalArgumentException("corroboration group must be non-empty");
        }
        Set<UUID> reporters = new HashSet<>();
        double aggregateWeight = 0.0;
        for (WeightedFeedback item : group) {
            reporters.add(item.personaId());
            aggregateWeight += item.weight();
        }
        boolean trusted = reporters.size() >= minCorroborators && aggregateWeight >= minWeight;
        return new CorroborationResult(reporters.size(), aggregateWeight, trusted);
    }
}
