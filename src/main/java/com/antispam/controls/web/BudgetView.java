package com.antispam.controls.web;

/**
 * The LLM spend caps as the console's budget control renders them (story 12.02).
 *
 * @param enabled       whether spend is actually gated (the hosted deploy; off in local/dev/tests)
 * @param dailyCapUsd   the most that may be spent in one UTC day
 * @param monthlyCapUsd the most that may be spent in one UTC calendar month
 */
public record BudgetView(boolean enabled, double dailyCapUsd, double monthlyCapUsd) {}
