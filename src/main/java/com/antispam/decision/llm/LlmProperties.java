package com.antispam.decision.llm;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The LLM fallback's knobs, bound from {@code antispam.llm} (story 05.02). They live in config,
 * not code, so the fallback can be switched on per environment and the per-token prices tuned
 * without a redeploy.
 *
 * @param enabled whether routed decisions actually call the provider. Off by default: local dev
 *                and the full-context tests never reach a provider, so a routed decision
 *                fail-degrades instead of attempting a network call with no API key. The hosted
 *                deploy sets it true alongside {@code OPENAI_API_KEY}.
 * @param cost    per-token prices used to record {@code llm_cost_usd} on each classification; the
 *                rolling budget cap that consumes the cost is story 05.04
 */
@Validated
@ConfigurationProperties(prefix = "antispam.llm")
public record LlmProperties(boolean enabled, Cost cost) {

    public LlmProperties {
        if (cost == null) {
            cost = new Cost(0.0, 0.0);
        }
    }

    /**
     * Per-1000-token prices in USD, used to turn a call's token usage into a recorded cost.
     *
     * @param inputPer1kTokens  USD per 1000 prompt (input) tokens
     * @param outputPer1kTokens USD per 1000 completion (output) tokens
     */
    public record Cost(double inputPer1kTokens, double outputPer1kTokens) {

        public Cost {
            if (inputPer1kTokens < 0.0 || outputPer1kTokens < 0.0) {
                throw new IllegalArgumentException(
                        "antispam.llm.cost.*-per-1k-tokens must be non-negative");
            }
        }
    }
}
