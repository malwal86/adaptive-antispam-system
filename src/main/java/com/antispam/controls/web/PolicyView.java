package com.antispam.controls.web;

import com.antispam.decision.policy.Policy;
import java.time.Instant;

/**
 * A policy as the console's left-rail selector renders it (story 12.02): its version, whether it is
 * the enforcing regime, and the tunable knobs the threshold sliders bind to.
 *
 * @param version            policy identifier, recorded on every decision made under it
 * @param active             whether this is the one enforcing regime
 * @param warnThreshold      posterior at/above which a mail is at least {@code warn}
 * @param quarantineThreshold posterior at/above which a mail is at least {@code quarantine}
 * @param blockThreshold     posterior at/above which a mail is {@code block}
 * @param llmThreshold       calibrated-confidence floor below which a decision is escalated to the LLM
 * @param routingBandWidth   half-width of the tier-boundary band that escalates to the LLM
 * @param burstThreshold     sender-velocity count above which a decision is escalated
 * @param modelVersion       the model artifact this regime is calibrated for
 * @param createdAt          when the policy was created
 */
public record PolicyView(
        String version,
        boolean active,
        double warnThreshold,
        double quarantineThreshold,
        double blockThreshold,
        double llmThreshold,
        double routingBandWidth,
        int burstThreshold,
        String modelVersion,
        Instant createdAt) {

    public static PolicyView from(Policy policy) {
        return new PolicyView(
                policy.version(),
                policy.active(),
                policy.warnThreshold(),
                policy.quarantineThreshold(),
                policy.blockThreshold(),
                policy.llmThreshold(),
                policy.routingBandWidth(),
                policy.burstThreshold(),
                policy.modelVersion(),
                policy.createdAt());
    }
}
