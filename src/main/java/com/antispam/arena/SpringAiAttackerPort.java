package com.antispam.arena;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Spring AI implementation of {@link AttackerPort} (story 08.01): it adapts the project's narrow
 * text-in/text-out attacker boundary onto Spring AI's {@link ChatClient}, just as
 * {@link com.antispam.decision.llm.SpringAiLlmChatPort} does for the defender. This is the only arena
 * class that knows about Spring AI, so the attacker provider is a config change, not a code edit.
 *
 * <p>The attacker model is set per call from {@link ArenaProperties#attackerModel()} via a portable
 * {@link ChatOptions}, so the red-team can run a different (often stronger) model than the defender's
 * fallback even though both share one Spring AI context. Availability is a precondition, not an error
 * to recover from: a disabled arena or an absent provider short-circuits with
 * {@link AttackerUnavailableException} and makes no network call, which is what keeps the keyless
 * local and CI builds attacker-free.
 */
@Component
public class SpringAiAttackerPort implements AttackerPort {

    private static final String SYSTEM_PREAMBLE = """
            You are a red-team adversary stress-testing an email spam filter. You are given the raw \
            text of a message that is known spam. Produce a single mutated variant that a filter is \
            more likely to miss while preserving the original malicious intent and call to action. \
            Apply ONLY the transformation described below. Return only the mutated raw email text, \
            with no commentary, preamble, or code fences.

            Transformation to apply: """;

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final ArenaProperties properties;

    @Autowired
    public SpringAiAttackerPort(
            ObjectProvider<ChatClient.Builder> chatClientBuilder, ArenaProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
    }

    @Override
    public String mutate(MutationStrategy strategy, String seedContent) {
        if (!properties.enabled()) {
            throw new AttackerUnavailableException("arena attacker disabled (antispam.arena.enabled=false)");
        }
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new AttackerUnavailableException("no LLM provider is configured on the context");
        }

        ChatResponse response;
        try {
            response = builder.build()
                    .prompt()
                    .options(ChatOptions.builder().model(properties.attackerModel()).build())
                    .system(SYSTEM_PREAMBLE + strategy.attackerInstruction())
                    .user(seedContent)
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            throw new AttackerUnavailableException("attacker call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new AttackerUnavailableException("attacker returned no result");
        }
        return response.getResult().getOutput().getText();
    }
}
