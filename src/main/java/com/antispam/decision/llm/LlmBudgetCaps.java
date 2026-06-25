package com.antispam.decision.llm;

import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The live, operator-adjustable LLM spend caps (story 12.02). {@link LlmBudgetProperties} seeds the
 * startup values from config; this holder lets the console raise or lower the daily/monthly caps at
 * runtime without a redeploy, and {@link RedisLlmBudget} reads the <em>current</em> caps on every
 * reservation so a change takes effect on the next LLM-routed decision.
 *
 * <p>Only the cap values are adjustable here; spend itself stays in Redis. The override is process-local
 * (it resets to the configured caps on restart) — a deliberate, simple choice for the demo's
 * single-instance deploy, not a distributed config store. The same invariants as the static config are
 * enforced: caps are non-negative and the daily cap cannot exceed the monthly cap.
 */
@Component
public class LlmBudgetCaps {

    /** An immutable snapshot of the two caps. */
    public record Caps(double dailyCapUsd, double monthlyCapUsd) {
        public Caps {
            if (dailyCapUsd < 0.0 || monthlyCapUsd < 0.0) {
                throw new IllegalArgumentException("LLM budget caps must be non-negative");
            }
            if (dailyCapUsd > monthlyCapUsd) {
                throw new IllegalArgumentException(
                        "LLM daily cap cannot exceed the monthly cap");
            }
        }
    }

    private final AtomicReference<Caps> caps;

    @Autowired
    public LlmBudgetCaps(LlmBudgetProperties properties) {
        this.caps = new AtomicReference<>(
                new Caps(properties.dailyCapUsd(), properties.monthlyCapUsd()));
    }

    /** The caps in force right now. */
    public Caps current() {
        return caps.get();
    }

    /** Replaces the caps in force. Validated by {@link Caps}; rejects an invalid pair atomically. */
    public Caps update(double dailyCapUsd, double monthlyCapUsd) {
        Caps next = new Caps(dailyCapUsd, monthlyCapUsd);
        caps.set(next);
        return next;
    }
}
