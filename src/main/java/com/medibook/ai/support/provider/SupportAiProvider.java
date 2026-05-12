package com.medibook.ai.support.provider;

/**
 * Port for the support AI text-generation provider.
 *
 * Implementations are selected via app.ai.support.provider property:
 *   stub      → StubSupportProvider  (always-on fallback)
 *   ollama    → OllamaSupportProvider (local inference server)
 *   claude-api → delegates to existing ClaudeAiChatClient via adapter
 */
public interface SupportAiProvider {

    /**
     * Generate a support response.
     *
     * @param systemPrompt  The safety-controlled system instruction
     * @param userMessage   The classified user message
     * @return              Generated text; never null. Returns a fallback string on error.
     */
    String generate(String systemPrompt, String userMessage);

    /** Provider identifier for audit logs */
    String providerId();
}
