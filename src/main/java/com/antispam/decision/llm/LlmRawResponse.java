package com.antispam.decision.llm;

/**
 * One LLM completion as it comes off the provider, before any parsing (story 05.02): the raw
 * assistant text and the call's token usage. The text is handed to the schema parser; the token
 * counts price the call into {@code llm_cost_usd} (the rolling budget cap that consumes the cost
 * is story 05.04).
 *
 * @param text             the assistant's raw response text (never null; empty if the model
 *                         returned no content, which the parser then treats as a schema failure)
 * @param promptTokens     tokens billed for the request, or 0 when the provider reports none
 * @param completionTokens tokens billed for the response, or 0 when the provider reports none
 */
public record LlmRawResponse(String text, long promptTokens, long completionTokens) {

    public LlmRawResponse {
        if (text == null) {
            text = "";
        }
        if (promptTokens < 0 || completionTokens < 0) {
            throw new IllegalArgumentException("token counts cannot be negative");
        }
    }
}
