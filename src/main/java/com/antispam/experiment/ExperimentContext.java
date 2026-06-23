package com.antispam.experiment;

import java.util.function.Supplier;

/**
 * Marks the current thread as a <b>read-only experiment</b> for the duration of a unit of work, so
 * that any attempt to mutate live state from within it is blocked at the write chokepoint (story
 * 09.03, PRD §Subsystem 8). This is the architectural enforcement of the side-effect isolation that
 * shadow testing (09.02), replay (09.01), and the adversarial arena (Epic 08) all depend on: an
 * experiment may freely <em>read</em> live reputation, features, and policies, but the live enforced
 * path is the only writer of {@code reputation_events}, the {@code senders} cache, {@code feedback_events},
 * and enforced {@code classifications}. Experiments write only their own experiment-scoped tables.
 *
 * <p><b>How it works.</b> An experiment runs its work through {@link #runReadOnly}/{@link #callReadOnly},
 * which sets a thread-bound flag for the call. Every repository that writes live state calls
 * {@link #requireLiveWritePermitted} first; inside a read-only scope that call throws
 * {@link LiveStateWriteForbiddenException} <em>before</em> the database is touched. Outside a scope it
 * is a no-op, so the live path pays nothing. The guard is enforcement, not convention — a stray
 * write (a refactor that reuses a live repository on an experiment path, a future arena variant that
 * forgets the invariant) fails loudly in a test rather than silently mutating production.
 *
 * <p><b>Thread-bound, not request-bound.</b> The flag lives in a {@link ThreadLocal}, because the
 * experiment paths run off the request thread: shadow scoring on its own executor (09.02), replay on
 * the Kafka consumer threads (09.01). Each thread carries its own scope and never sees another's, so
 * a read-only experiment running concurrently with the live path cannot block live writes on a
 * different thread. Nested scopes are harmless: the inner call reuses the outer scope and only the
 * outermost clears the flag on exit.
 *
 * <p>All-static with a private constructor: a thread-local marker has no per-instance state to inject,
 * and a single shared accessor keeps the read and write sides of the invariant in one place.
 */
public final class ExperimentContext {

    private static final ThreadLocal<Boolean> READ_ONLY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private ExperimentContext() {
    }

    /** Whether the current thread is inside a read-only experiment scope. */
    public static boolean isReadOnly() {
        return READ_ONLY.get();
    }

    /**
     * Runs {@code work} with the current thread marked read-only, restoring the prior state on exit —
     * including when {@code work} throws. The entry point an experiment wraps its scoring in so that
     * any live-state write attempted underneath is blocked.
     */
    public static void runReadOnly(Runnable work) {
        callReadOnly(() -> {
            work.run();
            return null;
        });
    }

    /**
     * Like {@link #runReadOnly} but returns the work's value — for an experiment unit that produces a
     * result (e.g. whether a replay decision was written). Nesting is a no-op: if the thread is
     * already read-only the work runs in the existing scope and the flag is left untouched, so only
     * the outermost call clears it.
     */
    public static <T> T callReadOnly(Supplier<T> work) {
        if (READ_ONLY.get()) {
            return work.get();
        }
        READ_ONLY.set(Boolean.TRUE);
        try {
            return work.get();
        } finally {
            READ_ONLY.remove();
        }
    }

    /**
     * Asserts the current thread may write live state, throwing if it is inside a read-only experiment
     * scope. Every live-state mutator calls this before its write; it is a no-op on the live path.
     *
     * @param table the live-state table about to be written, named in the exception for triage
     * @throws LiveStateWriteForbiddenException if called inside a read-only experiment scope
     */
    public static void requireLiveWritePermitted(String table) {
        if (READ_ONLY.get()) {
            throw new LiveStateWriteForbiddenException(table);
        }
    }
}
