package com.antispam.decision.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards the one part of story 05.02 that the unit tests cannot see and that the full-context
 * integration tests skip locally (no Docker): whether adding the Spring AI OpenAI starter still
 * lets the application boot <em>without</em> an API key — locally, in CI, and on a keyless deploy.
 *
 * <p>The OpenAI chat auto-config is on by default and eagerly builds a model bean that asserts a
 * non-blank key, so a blank key fails context startup. {@code application.yml} therefore defaults
 * {@code spring.ai.openai.api-key} to a non-blank placeholder; these tests pin both halves of that
 * reasoning so a future config change can't silently reintroduce the keyless-boot failure.
 */
class OpenAiChatBootContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    RestClientAutoConfiguration.class,
                    OpenAiChatAutoConfiguration.class,
                    ChatClientAutoConfiguration.class));

    @Test
    void boots_and_exposes_the_chat_model_and_builder_the_port_needs() {
        runner.withPropertyValues("spring.ai.openai.api-key=placeholder-no-llm-key")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ChatModel.class)
                        // The ChatClient.Builder SpringAiLlmChatPort injects is present once a model
                        // exists, so the LLM path actually works when enabled (not merely boots).
                        .hasSingleBean(ChatClient.Builder.class));
    }

    @Test
    void fails_to_boot_when_the_key_is_blank_which_is_why_the_default_is_a_placeholder() {
        runner.withPropertyValues("spring.ai.openai.api-key=")
                .run(context -> assertThat(context).hasFailed());
    }
}
