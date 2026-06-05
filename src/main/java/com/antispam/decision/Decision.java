package com.antispam.decision;

import java.util.Collection;
import java.util.Comparator;

/**
 * The four-tier verdict the pipeline can reach for an email, ordered from least
 * to most severe: {@code ALLOW < WARN < QUARANTINE < BLOCK}. Severity is the
 * enum's declaration order, so {@link #mostSevere} can pick the worst when
 * several signals fire on the same message.
 *
 * <p>Hard rules (story 01.04) only ever emit {@link #QUARANTINE} or {@link #BLOCK}.
 * The full tier set is the shared decision vocabulary the model/fusion path
 * (Epic 04) and policies (04.05) reuse — {@code warn} is the low-false-positive
 * lever (deliver with a banner) introduced once the model exists.
 */
public enum Decision {
    ALLOW,
    WARN,
    QUARANTINE,
    BLOCK;

    /**
     * The most severe decision in {@code decisions}.
     *
     * @throws IllegalArgumentException if {@code decisions} is empty — a caller
     *     should only merge decisions when at least one signal fired
     */
    public static Decision mostSevere(Collection<Decision> decisions) {
        return decisions.stream()
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalArgumentException("no decisions to merge"));
    }
}
