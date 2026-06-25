package com.antispam.controls.web;

/**
 * New LLM spend caps to apply (story 12.02). The non-negative and daily ≤ monthly invariants are
 * validated by {@code LlmBudgetCaps}, surfaced as a 400.
 *
 * @param dailyCapUsd   the most that may be spent in one UTC day
 * @param monthlyCapUsd the most that may be spent in one UTC calendar month
 */
public record BudgetCapsRequest(double dailyCapUsd, double monthlyCapUsd) {}
