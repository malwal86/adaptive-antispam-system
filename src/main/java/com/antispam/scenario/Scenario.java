package com.antispam.scenario;

import java.util.List;

/**
 * One named, scripted demo the console can run through the live pipeline. A scenario is a pure,
 * seeded function from a seed to an ordered list of {@link ScenarioEmail}s: same seed, same bytes, so
 * a memorable run reproduces exactly, while a different seed re-mutates the content so successive
 * runs aren't carbon copies. It touches no clock and no random source beyond the supplied seed, so it
 * is safe to build anywhere and trivial to test.
 *
 * <p>Implementations are stateless {@code @Component}s; {@link ScenarioCatalog} collects them by
 * {@link #name()} so {@link ScenarioService} can dispatch by name without a hard-coded {@code if}
 * chain. Adding a scenario is therefore a single new {@code @Component} — the runner, the API, and
 * the one-scenario-at-a-time guard all keep working unchanged.
 */
public interface Scenario {

    /** The stable id used in the API path and the console's picker; unique across the catalog. */
    String name();

    /**
     * Builds the scenario's ordered emails from {@code seed}. Deterministic in the seed.
     *
     * @return the emails to inject, in order; never empty
     */
    List<ScenarioEmail> build(long seed);
}
