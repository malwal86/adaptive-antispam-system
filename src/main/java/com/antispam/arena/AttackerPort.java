package com.antispam.arena;

/**
 * The narrow boundary between the mutation engine and whatever LLM provider plays the attacker
 * (story 08.01). Like {@link com.antispam.decision.llm.LlmChatPort} it is the seam the project owns:
 * {@link MutationService} depends on this interface, not on Spring AI's types, so the attacker model
 * is swappable by configuration and the engine is unit-testable against a deterministic stub without
 * a network or an API key.
 *
 * <p>The port deals only in text: a real seed spam in, a perturbed variant out. Choosing the seed,
 * recording the strategy and lineage, and persisting the variant are the service's job — the port
 * knows only how to ask the configured attacker model to apply one perturbation.
 */
public interface AttackerPort {

    /**
     * Perturbs {@code seedContent} by applying {@code strategy}, using the configured attacker model.
     *
     * @param strategy    the perturbation to apply; supplies the model's strategy-specific instruction
     * @param seedContent the raw text of the real seed spam to mutate (never invented from scratch)
     * @return the mutated raw email text
     * @throws AttackerUnavailableException if the arena is disabled, no provider is configured, or the
     *                                      call fails at the transport layer
     */
    String mutate(MutationStrategy strategy, String seedContent);
}
