package com.antispam.arena;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the adversarial arena. The {@code attackerModel} is the PRD's configurable
 * {@code attacker_model} (§Subsystem 6, story 08.01): the red-team's model is a config value, not a
 * code constant, so it can be swapped per run without an edit. {@code enabled} gates the live
 * attacker the same way {@code antispam.llm.enabled} gates the defender's fallback, so local dev and
 * the full-context tests stay provider-free unless explicitly turned on.
 *
 * <p>The bounded loop's defaults (story 08.02): the hard {@code generationCap} (3–5) and per-run
 * {@code budgetUsd} ceiling that make the loop terminate by construction, and the
 * {@code costPerMutationUsd} charged against that ceiling per attacker call. A run may override the cap
 * and budget, but the defaults make "even my red-team has a budget" the out-of-the-box behavior — and,
 * like the LLM budget caps, they are tunable without a redeploy.
 *
 * <p>The last two fields configure bypass measurement (story 08.04). {@code baselinePolicyVersion}
 * names the fixed reference defender a run's variants are also scored against for the "danger missed by
 * baseline" comparison; left blank it defaults to the genesis policy
 * ({@link com.antispam.decision.policy.PolicyRepository#findOldest()}), so the baseline is a stable
 * anchor across runs without any config. {@code corpusLabelWeight} is the training weight stamped on
 * every bypassing variant fed into the retrain corpus.
 *
 * @param enabled               whether the live attacker port may call a provider; default off
 * @param attackerModel         the model name the attacker port requests; recorded on every variant
 * @param generationCap         default hard cap on generations per run (3–5); default 3
 * @param budgetUsd             default hard spend ceiling per run, in USD; default 1.00
 * @param costPerMutationUsd    modeled cost of one attacker call, charged against the run budget; default 0.01
 * @param baselinePolicyVersion the fixed baseline defender to compare against; blank → the genesis policy
 * @param corpusLabelWeight     training weight of a bypassing variant in the retrain corpus; default 1.0
 */
@ConfigurationProperties(prefix = "antispam.arena")
public record ArenaProperties(
        boolean enabled,
        String attackerModel,
        int generationCap,
        BigDecimal budgetUsd,
        BigDecimal costPerMutationUsd,
        String baselinePolicyVersion,
        double corpusLabelWeight) {

    public ArenaProperties {
        if (attackerModel == null || attackerModel.isBlank()) {
            attackerModel = "gpt-4o-mini";
        }
        if (generationCap < 1) {
            generationCap = 3;
        }
        if (budgetUsd == null || budgetUsd.signum() <= 0) {
            budgetUsd = new BigDecimal("1.00");
        }
        if (costPerMutationUsd == null || costPerMutationUsd.signum() < 0) {
            costPerMutationUsd = new BigDecimal("0.01");
        }
        if (corpusLabelWeight <= 0 || Double.isNaN(corpusLabelWeight)) {
            corpusLabelWeight = 1.0;
        }
    }
}
