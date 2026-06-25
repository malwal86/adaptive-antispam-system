package com.antispam.scenario;

/**
 * Runs a scenario's injection loop off the caller's thread. Extracted to a one-method seam so the
 * runner's orchestration ({@link ScenarioService}) can be exercised synchronously in tests — a test
 * supplies a dispatcher that runs the task inline — while production dispatches it to a background
 * thread so {@code POST .../start} returns immediately and the beats unfold over time on the stream.
 */
@FunctionalInterface
public interface ScenarioDispatcher {

    /** Runs {@code task}; production runs it asynchronously, tests run it inline. */
    void dispatch(Runnable task);
}
