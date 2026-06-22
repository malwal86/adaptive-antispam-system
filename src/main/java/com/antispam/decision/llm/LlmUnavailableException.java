package com.antispam.decision.llm;

/**
 * Signals that the LLM could not be reached at all (story 05.02): the fallback is disabled, no
 * provider is configured, or the call failed at the transport layer. It is distinct from a
 * received-but-malformed response on purpose — a transport failure is not a schema problem, so the
 * service does not burn its single retry on it; it fail-degrades directly. Retrying transient
 * transport errors is a deliberate non-goal of this story (the retry budget is reserved for the
 * schema-failure path the acceptance criteria specify).
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
