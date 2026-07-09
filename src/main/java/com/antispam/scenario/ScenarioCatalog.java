package com.antispam.scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The registry of runnable scenarios, keyed by {@link Scenario#name()}. Spring injects every
 * {@link Scenario} {@code @Component}, so a new scenario appears here — and thus in the runner and the
 * API — merely by existing, with no wiring change. Insertion order is preserved so the catalog reads
 * in the order the beans are declared.
 *
 * <p>Two scenarios sharing a name would make dispatch ambiguous, so construction fails fast on a
 * duplicate rather than silently letting one shadow the other.
 */
@Component
public class ScenarioCatalog {

    private final Map<String, Scenario> byName = new LinkedHashMap<>();

    @Autowired
    public ScenarioCatalog(List<Scenario> scenarios) {
        for (Scenario scenario : scenarios) {
            Scenario clash = byName.putIfAbsent(scenario.name(), scenario);
            if (clash != null) {
                throw new IllegalStateException("duplicate scenario name: " + scenario.name());
            }
        }
    }

    /** The scenario registered under {@code name}, or empty when no scenario claims it. */
    public Optional<Scenario> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** Every registered scenario, in declaration order. */
    public List<Scenario> all() {
        return List.copyOf(byName.values());
    }
}
