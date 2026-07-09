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

    /**
     * Senders this scenario wants pre-warmed <em>before</em> its mail flows, so their first email
     * lands as a confident, model-route decision rather than dwelling in the LLM's quarantine-pending
     * queue. A brand-new sender's reputation is maximally uncertain, so the router escalates its mail
     * to the LLM regardless of content ({@code NEW_SENDER_UNCERTAINTY}); seeding a few authenticated
     * good signals drops that uncertainty below the routing band, so genuinely benign mail from a
     * known-good sender is allowed instantly. The runner ({@link ScenarioService}) applies these as
     * authenticated {@code GOOD} reputation before dispatching the script.
     *
     * <p>Default: none — a scenario whose whole point is unseen senders (the thunderclap's cold-start
     * warm-up) overrides nothing and every sender stays new.
     *
     * @return the senders to pre-warm, or an empty list to warm none
     */
    default List<SenderWarmup> prewarm() {
        return List.of();
    }

    /**
     * A sender to seed with authenticated good reputation before a scenario runs.
     *
     * @param senderKey the sender identity as {@link com.antispam.event.SenderKey#of} derives it
     *                  (the normalized, lower-cased From address, else the domain) — must match the
     *                  scenario email's sender exactly, or the warm-up lands on a different key
     * @param weight    how much good evidence to seed; one high-weight signal (≈20) is enough to
     *                  drop the sender's uncertainty below the routing band in a single record
     */
    record SenderWarmup(String senderKey, double weight) {

        public SenderWarmup {
            if (senderKey == null || senderKey.isBlank()) {
                throw new IllegalArgumentException("senderKey must not be blank");
            }
            if (weight <= 0) {
                throw new IllegalArgumentException("weight must be positive but was " + weight);
            }
        }
    }
}
