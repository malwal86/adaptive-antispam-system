package com.antispam.decision.policy;

import com.antispam.decision.Decision;
import java.time.Instant;

/**
 * A decision regime (PRD §Data Model, story 04.05): the bundle of tunable parameters
 * that turn a fused posterior into a verdict, versioned and flagged {@link #active()} so
 * exactly one regime enforces at a time. Bundling the thresholds (and the LLM-routing
 * threshold Epic 05 consumes) into one versioned row is what makes decisioning
 * policy-driven rather than hardcoded — and what lets shadow/replay (Epic 09) and the
 * retrain promotion loop (Epic 10) compare or flip regimes by changing data, not code.
 *
 * <p>The three tier thresholds are the posterior cut-points of the four-tier ladder
 * {@code allow < warn < quarantine < block}; they must be a non-decreasing ladder within
 * {@code [0,1]} so {@link #tierFor} is well-defined. Each threshold is the
 * <em>inclusive floor</em> of its tier: a posterior exactly on a threshold lands in the
 * more severe tier, so the gentler tier is a half-open interval below it.
 *
 * @param version            the policy's identifier, recorded as {@code policy_version} on
 *                           every decision made under it
 * @param active             whether this is the one enforcing regime
 * @param warnThreshold      posterior at/above which a mail is at least {@code warn}
 * @param quarantineThreshold posterior at/above which a mail is at least {@code quarantine}
 * @param blockThreshold     posterior at/above which a mail is {@code block}
 * @param llmThreshold       the LLM-routing confidence floor consumed by Epic 05: a model whose
 *                           calibrated confidence falls below it is escalated. Carried here so the
 *                           whole regime is one versioned bundle; not used by {@link #tierFor}
 * @param routingBandWidth   the LLM-routing boundary band half-width (story 05.01): a posterior
 *                           within this distance of a tier cut-point is escalated, the band
 *                           widened further at decide time by the sender's reputation uncertainty.
 *                           Not used by {@link #tierFor}
 * @param modelVersion       the model artifact this regime is calibrated for
 * @param createdAt          when the policy was created
 */
public record Policy(
        String version,
        boolean active,
        double warnThreshold,
        double quarantineThreshold,
        double blockThreshold,
        double llmThreshold,
        double routingBandWidth,
        String modelVersion,
        Instant createdAt) {

    public Policy {
        requireUnit("warnThreshold", warnThreshold);
        requireUnit("quarantineThreshold", quarantineThreshold);
        requireUnit("blockThreshold", blockThreshold);
        requireUnit("llmThreshold", llmThreshold);
        requireUnit("routingBandWidth", routingBandWidth);
        if (!(warnThreshold <= quarantineThreshold && quarantineThreshold <= blockThreshold)) {
            throw new IllegalArgumentException(String.format(
                    "tier thresholds must be a non-decreasing ladder but were warn=%.4f "
                            + "quarantine=%.4f block=%.4f", warnThreshold, quarantineThreshold, blockThreshold));
        }
    }

    /**
     * The tier this policy assigns to {@code posterior}: the most severe tier whose
     * inclusive-floor threshold the posterior reaches.
     */
    public Decision tierFor(double posterior) {
        if (posterior >= blockThreshold) {
            return Decision.BLOCK;
        }
        if (posterior >= quarantineThreshold) {
            return Decision.QUARANTINE;
        }
        if (posterior >= warnThreshold) {
            return Decision.WARN;
        }
        return Decision.ALLOW;
    }

    private static void requireUnit(String name, double value) {
        if (value < 0.0 || value > 1.0 || Double.isNaN(value)) {
            throw new IllegalArgumentException(name + " must be in [0,1] but was " + value);
        }
    }
}
