package com.antispam.eval;

import java.util.Map;
import java.util.UUID;

/**
 * The outcome of a split: every email's side, plus the {@link SplitAudit} that
 * proves the assignment is leakage-free. Immutable — a split is a fact about a
 * corpus at a configuration, not a mutable workspace.
 *
 * @param sides   email id to the side it was assigned; iteration order is
 *                unspecified
 * @param groups  email id to the effective family key it was grouped under (the
 *                supplied {@code groupId}, or a synthesized singleton key) — kept so
 *                the persisted assignment can record why two emails moved together
 * @param audit   the leakage-free evidence for this split
 */
public record EvalSplit(Map<UUID, SplitSide> sides, Map<UUID, String> groups, SplitAudit audit) {

    public EvalSplit {
        sides = Map.copyOf(sides);
        groups = Map.copyOf(groups);
    }

    /** The side {@code emailId} was assigned, or null if it was not in the corpus. */
    public SplitSide sideOf(UUID emailId) {
        return sides.get(emailId);
    }
}
