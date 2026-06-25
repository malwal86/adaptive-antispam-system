package com.antispam.controls.web;

/**
 * The threshold-slider values to apply (story 12.02). Applying them mints a new policy version with
 * these tunables (carrying the active policy's burst threshold and model) and activates it, so
 * subsequent decisions use the new cut-points. The non-decreasing ladder and {@code [0,1]} bounds are
 * validated by the {@code Policy} record, surfaced as a 400.
 *
 * @param warnThreshold      posterior at/above which a mail is at least {@code warn}
 * @param quarantineThreshold posterior at/above which a mail is at least {@code quarantine}
 * @param blockThreshold     posterior at/above which a mail is {@code block}
 * @param llmThreshold       calibrated-confidence floor below which a decision is escalated to the LLM
 * @param routingBandWidth   half-width of the tier-boundary band that escalates to the LLM
 */
public record ThresholdsRequest(
        double warnThreshold,
        double quarantineThreshold,
        double blockThreshold,
        double llmThreshold,
        double routingBandWidth) {}
