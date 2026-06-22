package com.antispam.decision.llm;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The Spring AI implementation of the {@link LlmChatPort} (story 05.02): it adapts the project's
 * narrow text-in/text-out boundary onto Spring AI's {@link ChatClient}. This is the only class
 * that knows about Spring AI, so swapping the provider is a build/config change (a different
 * {@code spring-ai-starter-model-*} and {@code spring.ai.*} block) with no edit to the decision
 * path — the pluggability PRD §Subsystem 5 calls a demo feature.
 *
 * <p><b>Availability is a precondition, not an error to recover from.</b> The port short-circuits
 * with {@link LlmUnavailableException} when the fallback is disabled or no provider bean is on the
 * context, so the disabled path makes <em>no</em> network call — that is what keeps local dev and
 * the full-context tests provider-free. A transport failure on a real call is also surfaced as
 * {@link LlmUnavailableException}; the service degrades on it rather than spending its schema
 * retry.
 */
@Component
public class SpringAiLlmChatPort implements LlmChatPort {

    private final ObjectProvider<ChatClient.Builder> chatClientBuilder;
    private final LlmProperties properties;

    @Autowired
    public SpringAiLlmChatPort(
            ObjectProvider<ChatClient.Builder> chatClientBuilder, LlmProperties properties) {
        this.chatClientBuilder = chatClientBuilder;
        this.properties = properties;
    }

    @Override
    public LlmRawResponse complete(String systemInstruction, String userContent) {
        if (!properties.enabled()) {
            throw new LlmUnavailableException("LLM fallback disabled (antispam.llm.enabled=false)");
        }
        ChatClient.Builder builder = chatClientBuilder.getIfAvailable();
        if (builder == null) {
            throw new LlmUnavailableException("no LLM provider is configured on the context");
        }

        ChatResponse response;
        try {
            response = builder.build()
                    .prompt()
                    .system(systemInstruction)
                    .user(userContent)
                    .call()
                    .chatResponse();
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("LLM call failed: " + e.getMessage(), e);
        }
        if (response == null || response.getResult() == null) {
            throw new LlmUnavailableException("LLM returned no result");
        }

        String text = response.getResult().getOutput().getText();
        return new LlmRawResponse(text, promptTokens(response), completionTokens(response));
    }

    private static long promptTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null || usage.getPromptTokens() == null ? 0L : usage.getPromptTokens();
    }

    private static long completionTokens(ChatResponse response) {
        Usage usage = usage(response);
        return usage == null || usage.getCompletionTokens() == null ? 0L : usage.getCompletionTokens();
    }

    private static Usage usage(ChatResponse response) {
        return response.getMetadata() == null ? null : response.getMetadata().getUsage();
    }
}
