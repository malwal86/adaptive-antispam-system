package com.antispam.decision.llm;

/**
 * The narrow boundary between the decision pipeline and whatever LLM provider is configured
 * (story 05.02). It is the seam the project owns: {@link LlmFallbackService} depends on this
 * interface, not on Spring AI's types, so the provider is swappable by configuration (PRD
 * §Subsystem 5 — "provider swap is a demo feature") and the retry/fail-degrade state machine is
 * unit-testable against a stub without a network or an API key.
 *
 * <p>The port deals only in raw text plus token usage; it does no parsing or validation —
 * turning the text into a typed {@link LlmVerdict}, retrying, and degrading is the service's job.
 * Keeping the boundary this thin is deliberate: the only provider-specific knowledge (how to call
 * the model, how to read its token usage) lives behind it, and nothing else does.
 */
public interface LlmChatPort {

    /**
     * Sends one completion request and returns the model's raw response.
     *
     * @param systemInstruction the system prompt (the hardened instructions + output schema)
     * @param userContent       the untrusted email content, delimited as data
     * @return the raw assistant text and the call's token usage
     * @throws LlmUnavailableException if no provider is configured/enabled or the call fails at
     *                                 the transport layer — a different failure mode from a
     *                                 received-but-malformed response, which the service retries
     */
    LlmRawResponse complete(String systemInstruction, String userContent);
}
