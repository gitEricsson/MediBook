package com.medibook.ai.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub AI chat client for local development and testing.
 * Returns deterministic responses without calling any external API.
 *
 * Active when: app.intelligence.nlp-provider=stub (default)
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.intelligence.nlp-provider", havingValue = "stub", matchIfMissing = true)
public class StubAiChatClient implements AiChatClient {

    private static final String STUB_MODEL = "stub-v1";

    @Override
    public String modelId() { return STUB_MODEL; }

    @Override
    public AiChatResponse complete(String systemPrompt, String userMessage, int maxTokens) {
        log.info("[STUB] AI chat request — user message length={}", userMessage.length());

        String text;
        String lower = userMessage.toLowerCase();

        if (lower.contains("intake") || lower.contains("question")) {
            text = """
                    Thank you for the information. I've noted your responses for your doctor to review \
                    before your appointment. Is there anything else you'd like to add?

                    *(AI-generated — not a substitute for professional medical advice)*
                    """;
        } else if (lower.contains("summary") || lower.contains("summarize")) {
            text = """
                    **Chief Complaint**: Patient has described general health concerns.
                    **Symptoms Mentioned**: As reported in conversation.
                    **Red Flags**: None identified in this stub response.
                    **Unanswered Questions**: Review full conversation for open items.

                    *(AI-generated — not a substitute for professional medical advice)*
                    """;
        } else {
            text = """
                    Thank you for your message. I'm here to help with appointment preparation \
                    and general questions. For any medical concerns, please raise them directly \
                    with your doctor during your appointment.

                    *(AI-generated — not a substitute for professional medical advice)*
                    """;
        }

        return AiChatResponse.builder()
                .text(text)
                .model(STUB_MODEL)
                .inputTokens(userMessage.length() / 4)
                .outputTokens(text.length() / 4)
                .fallback(false)
                .build();
    }
}
