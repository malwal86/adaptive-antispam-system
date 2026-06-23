package com.antispam.decision.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The LLM budget cap's knobs (story 05.04), bound from {@code antispam.llm.budget}. They live in
 * config, not code, so the caps can be tuned per environment without a redeploy (PRD §Cost: cost is
 * a first-class, operable feature).
 *
 * @param enabled              whether spend is gated at all. Off by default: local dev and the
 *                             full-context tests wire the no-op {@link UnboundedLlmBudget}, so no
 *                             call ever dials Redis to check a budget. The hosted deploy sets it
 *                             true alongside {@code APP_REDIS_URL}.
 * @param dailyCapUsd          the daily sub-cap — the most that may be spent in one UTC day, so a
 *                             bad/adversarial day cannot burn the whole month
 * @param monthlyCapUsd        the rolling monthly cap — the most that may be spent in one UTC
 *                             calendar month
 * @param perCallReservationUsd the upper-bound amount reserved before each call (covering its one
 *                             retry). Must be ≥ the largest realistic cost of a single call, since
 *                             it is what bounds the running total between reserve and reconcile —
 *                             set it too low and a costly call could slip the cap; the actual spend
 *                             is trued up afterwards, so a generous value only ties budget up
 *                             briefly while a call is in flight.
 */
@Validated
@ConfigurationProperties(prefix = "antispam.llm.budget")
public record LlmBudgetProperties(
        boolean enabled, double dailyCapUsd, double monthlyCapUsd, double perCallReservationUsd) {

    public LlmBudgetProperties {
        if (dailyCapUsd < 0.0 || monthlyCapUsd < 0.0) {
            throw new IllegalArgumentException("antispam.llm.budget caps must be non-negative");
        }
        if (perCallReservationUsd <= 0.0) {
            throw new IllegalArgumentException(
                    "antispam.llm.budget.per-call-reservation-usd must be positive");
        }
        if (dailyCapUsd > monthlyCapUsd) {
            throw new IllegalArgumentException(
                    "antispam.llm.budget.daily-cap-usd cannot exceed monthly-cap-usd");
        }
    }
}
