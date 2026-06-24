package com.antispam.arena;

/**
 * Thrown by an {@link AttackerPort} when it cannot produce a mutation because the arena is disabled,
 * no LLM provider is configured, or the underlying call fails at the transport layer (story 08.01).
 * It is the attacker-side analogue of {@link com.antispam.decision.llm.LlmUnavailableException}: a
 * precondition/transport failure, distinct from a successful-but-unusable response, so the web layer
 * can surface it as "the attacker is unavailable" (HTTP 503) rather than a bad request.
 */
public class AttackerUnavailableException extends RuntimeException {

    public AttackerUnavailableException(String message) {
        super(message);
    }

    public AttackerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
