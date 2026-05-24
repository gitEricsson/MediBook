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
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * Google Gemini AI chat client for the consultation chat (drafts, summaries,
 * urgency detection).
 *
 * Active when: app.intelligence.nlp-provider=gemini
 * Requires:    app.ai.gemini.api-key (env: GEMINI_API_KEY)
 *
 * Uses the same Gemini API key already configured for the support chatbot.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.intelligence.nlp-provider", havingValue = "gemini")
public class GeminiAiChatClient implements AiChatClient {

    private static final String API_BASE      = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";

    @Value("${app.ai.gemini.api-key:#{null}}")
    private String apiKey;

    @Value("${app.intelligence.gemini-model:" + DEFAULT_MODEL + "}")
    private String model;

    private final ObjectMapper objectMapper;

    public GeminiAiChatClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String modelId() {
        return model != null && !model.isBlank() ? model : DEFAULT_MODEL;
    }

    @Override
    @CircuitBreaker(name = "clinicalNlpProvider", fallbackMethod = "fallback")
    public AiChatResponse complete(String systemPrompt, String userMessage, int maxTokens) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GeminiAiChatClient: GEMINI_API_KEY not configured — returning fallback");
            return AiChatResponse.fallback(modelId());
        }

        String resolvedModel = modelId();
        String url = API_BASE + resolvedModel + ":generateContent?key=" + apiKey;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                    "contents",          List.of(Map.of(
                            "role", "user",
                            "parts", List.of(Map.of("text", userMessage))
                    )),
                    "generationConfig",  Map.of("maxOutputTokens", maxTokens)
            ));

            String raw = RestClient.builder()
                    .baseUrl(url)
                    .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                    .build()
                    .post()
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);

            // Gemini response: candidates[0].content.parts[0].text
            String text = root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText("");

            // Gemini returns token counts in usageMetadata
            int inTok  = root.path("usageMetadata").path("promptTokenCount").asInt(0);
            int outTok = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);

            if (text.isBlank()) {
                log.warn("GeminiAiChatClient: empty response from Gemini (model={})", resolvedModel);
                return AiChatResponse.fallback(resolvedModel);
            }

            return AiChatResponse.builder()
                    .text(text)
                    .model(resolvedModel)
                    .inputTokens(inTok)
                    .outputTokens(outTok)
                    .fallback(false)
                    .build();

        } catch (RestClientResponseException ex) {
            log.error("GeminiAiChatClient HTTP {} (model={}): {}",
                    ex.getStatusCode(), resolvedModel, ex.getResponseBodyAsString());
            return AiChatResponse.fallback(resolvedModel);
        } catch (Exception ex) {
            log.error("GeminiAiChatClient error (model={}): {}", resolvedModel, ex.getMessage());
            return AiChatResponse.fallback(resolvedModel);
        }
    }

    /** Circuit breaker fallback */
    @SuppressWarnings("unused")
    public AiChatResponse fallback(String systemPrompt, String userMessage, int maxTokens, Throwable t) {
        log.warn("CircuitBreaker OPEN for Gemini AI chat: {}", t.getMessage());
        return AiChatResponse.fallback(modelId());
    }
}
