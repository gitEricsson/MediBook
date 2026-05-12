package com.medibook.ai.client;

/**
 * Port (interface) for the AI chat completion client.
 * Implementations: ClaudeAiChatClient, StubAiChatClient.
 */
public interface AiChatClient {

    /**
     * Send a chat completion request and return the response text.
     *
     * @param systemPrompt  Safety-controlled system prompt from PromptTemplateService
     * @param userMessage   The user's message or conversation context
     * @param maxTokens     Maximum tokens to generate
     * @return              AI-generated text (never null; returns fallback on error)
     */
    AiChatResponse complete(String systemPrompt, String userMessage, int maxTokens);

    /** Returns the model identifier for audit logging */
    String modelId();
}
