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

    /**
     * Generate a support response with conversation history for context continuity.
     *
     * @param systemPrompt       The safety-controlled system instruction
     * @param conversationHistory Previous messages as role/content pairs (oldest first)
     * @param userMessage         The current user message
     * @return                    Generated text; never null.
     */
    default String generate(String systemPrompt, java.util.List<java.util.Map<String, String>> conversationHistory, String userMessage) {
        // Default implementation ignores history for backward compatibility
        return generate(systemPrompt, userMessage);
    }

    /** Provider identifier for audit logs */
    String providerId();
}
