package com.medibook.ai.support.provider;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Google Gemini support assistant provider. Powers the in-app support chatbot
 * (same role as {@link ClaudeSupportProvider} but talks to Google's
 * generativelanguage API instead of Anthropic).
 *
 * Active when: app.ai.support.provider=gemini
 * Requires:    app.ai.gemini.api-key (env: GEMINI_API_KEY)
 *
 * API reference:
 *   POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={key}
 *
 * Note on history: Gemini takes a single `contents[]` array of role+parts where
 * the system prompt goes into a separate `systemInstruction` field. Roles are
 * "user" and "model" (not "assistant" — careful when porting Claude-shaped code).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.support.provider", havingValue = "gemini")
public class GeminiSupportProvider implements SupportAiProvider {

    private static final String API_BASE      = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String DEFAULT_MODEL = "gemini-2.5-flash";
    private static final String FALLBACK_REPLY =
            "I'm temporarily unable to reach the assistant. Please try again in a moment.";

    @Value("${app.ai.gemini.api-key:#{null}}")
    private String apiKey;

    @Value("${app.ai.support.max-tokens:512}")
    private int maxTokens;

    /** Override model id without redeploying — e.g. when gemini-2.5-pro becomes the right call. */
    @Value("${app.ai.support.gemini-model:gemini-2.5-flash}")
    private String model;

    private final ObjectMapper objectMapper;

    public GeminiSupportProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() { return "gemini"; }

    @Override
    public String generate(String systemPrompt, String userMessage) {
        return generate(systemPrompt, List.of(), userMessage);
    }

    @Override
    @CircuitBreaker(name = "supportAiProvider", fallbackMethod = "fallback")
    public String generate(String systemPrompt, List<Map<String, String>> conversationHistory, String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GeminiSupportProvider: GEMINI_API_KEY not set — returning fallback");
            return FALLBACK_REPLY;
        }

        // Build contents[] in Gemini's role/parts shape. Skip blanks, map any
        // "assistant"/"ai"/"bot" alias to Gemini's "model" role.
        List<Map<String, Object>> contents = new ArrayList<>();
        if (conversationHistory != null) {
            for (Map<String, String> turn : conversationHistory) {
                String role    = turn.getOrDefault("role", "user");
                String content = turn.getOrDefault("content", "");
                if (content.isBlank()) continue;
                String mapped = role.equalsIgnoreCase("assistant")
                        || role.equalsIgnoreCase("ai")
                        || role.equalsIgnoreCase("bot")
                        || role.equalsIgnoreCase("model")
                        ? "model" : "user";
                contents.add(Map.of(
                        "role", mapped,
                        "parts", List.of(Map.of("text", content))
                ));
            }
        }
        contents.add(Map.of(
                "role", "user",
                "parts", List.of(Map.of("text", userMessage))
        ));

        String resolvedModel = model != null && !model.isBlank() ? model : DEFAULT_MODEL;
        String url = API_BASE + resolvedModel + ":generateContent?key=" + apiKey;

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))),
                    "contents",          contents,
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
            // Gemini response shape: candidates[0].content.parts[0].text
            String text = root.path("candidates").path(0).path("content")
                    .path("parts").path(0).path("text").asText("");
            return text.isBlank() ? FALLBACK_REPLY : text;

        } catch (RestClientResponseException ex) {
            // Surface real Gemini error bodies (model-not-found, quota, auth, etc.)
            // — never log the full key. Truncate to first 8 chars + suffix length.
            log.error("GeminiSupportProvider HTTP {} from Gemini (model={}, keyPrefix={}): {}",
                    ex.getStatusCode(),
                    resolvedModel,
                    apiKey.length() > 12 ? apiKey.substring(0, 8) + "…" : "<short>",
                    ex.getResponseBodyAsString());
            return FALLBACK_REPLY;
        } catch (Exception ex) {
            log.error("GeminiSupportProvider error (model={}): {}", resolvedModel, ex.getMessage(), ex);
            return FALLBACK_REPLY;
        }
    }

    @SuppressWarnings("unused")
    public String fallback(String systemPrompt, List<Map<String, String>> history, String userMessage, Throwable t) {
        log.warn("CircuitBreaker OPEN for Gemini support provider: {}", t.getMessage());
        return FALLBACK_REPLY;
    }
}
