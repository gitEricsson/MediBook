package com.medibook.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * Claude (Anthropic) AI chat client.
 *
 * Active when: app.intelligence.nlp-provider=claude-api
 * Requires: app.intelligence.claude-api.key (env: CLAUDE_API_KEY)
 *
 * BAA NOTE: An executed BAA with Anthropic is required before handling PHI.
 * Set AI_PROVIDER_BAA_ACTIVE=true only after BAA is confirmed.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.chat.provider", havingValue = "claude")
public class ClaudeAiChatClient implements AiChatClient {

    private static final String API_URL      = "https://api.anthropic.com/v1/messages";
    private static final String MODEL        = "claude-haiku-4-5-20251001";
    private static final String ANTHROPIC_VER = "2023-06-01";

    @Value("${app.intelligence.claude-api.key:#{null}}")
    private String apiKey;

    private final ObjectMapper objectMapper;

    public ClaudeAiChatClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelId() { return MODEL; }

    @Override
    @CircuitBreaker(name = "clinicalNlpProvider", fallbackMethod = "fallback")
    public AiChatResponse complete(String systemPrompt, String userMessage, int maxTokens) {
        if (!isConfigured()) {
            log.warn("ClaudeAiChatClient: API key not configured — returning fallback");
            return AiChatResponse.fallback(MODEL);
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "model",      MODEL,
                    "max_tokens", maxTokens,
                    "system",     systemPrompt,
                    "messages",   List.of(Map.of("role", "user", "content", userMessage))
            ));

            String raw = RestClient.builder()
                    .baseUrl(API_URL)
                    .defaultHeader("x-api-key", apiKey)
                    .defaultHeader("anthropic-version", ANTHROPIC_VER)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root     = objectMapper.readTree(raw);
            String   text     = root.path("content").get(0).path("text").asText("");
            int      inTok    = root.path("usage").path("input_tokens").asInt(0);
            int      outTok   = root.path("usage").path("output_tokens").asInt(0);

            return AiChatResponse.builder()
                    .text(text)
                    .model(MODEL)
                    .inputTokens(inTok)
                    .outputTokens(outTok)
                    .fallback(false)
                    .build();

        } catch (Exception ex) {
            log.error("ClaudeAiChatClient error: {}", ex.getMessage());
            return AiChatResponse.fallback(MODEL);
        }
    }

    /** Circuit breaker fallback */
    public AiChatResponse fallback(String systemPrompt, String userMessage, int maxTokens, Throwable t) {
        log.warn("CircuitBreaker OPEN for Claude AI chat: {}", t.getMessage());
        return AiChatResponse.fallback(MODEL);
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
