package com.medibook.ai.support.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Ollama local inference provider.
 *
 * Active when: app.ai.support.provider=ollama
 * Requires: Ollama server running at OLLAMA_BASE_URL (default: http://localhost:11434)
 *
 * Uses the /api/chat endpoint (system + user message format).
 * Recommended models: llama3.1, mistral, qwen2.5
 *
 * No per-message cost; runs fully server-side. Suitable for development,
 * internal testing, and non-PHI support queries.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.ai.support.provider", havingValue = "ollama")
public class OllamaSupportProvider implements SupportAiProvider {

    private static final String FALLBACK_REPLY =
            "I'm temporarily unable to connect to the assistant. " +
            "Please try again shortly or contact MediBook support.";

    @Value("${app.ai.support.ollama.base-url:http://localhost:11434}")
    private String baseUrl;

    @Value("${app.ai.support.ollama.model:llama3.1}")
    private String model;

    @Value("${app.ai.support.max-tokens:512}")
    private int maxTokens;

    @Value("${app.ai.support.ollama.timeout-seconds:30}")
    private int timeoutSeconds;

    private final ObjectMapper objectMapper;

    public OllamaSupportProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public String providerId() {
        return "ollama/" + model;
    }

    @Override
    @CircuitBreaker(name = "ollamaProvider", fallbackMethod = "fallback")
    public String generate(String systemPrompt, String userMessage) {
        return generate(systemPrompt, List.of(), userMessage);
    }

    @Override
    @CircuitBreaker(name = "ollamaProvider", fallbackMethod = "fallbackWithHistory")
    public String generate(String systemPrompt, List<Map<String, String>> conversationHistory, String userMessage) {
        try {
            List<Map<String, String>> messages = new java.util.ArrayList<>();
            messages.add(Map.of("role", "system", "content", systemPrompt));
            messages.addAll(conversationHistory);
            messages.add(Map.of("role", "user", "content", userMessage));

            String requestBody = objectMapper.writeValueAsString(Map.of(
                    "model",    model,
                    "stream",   false,
                    "messages", messages,
                    "options",  Map.of("num_predict", maxTokens, "temperature", 0.7)
            ));

            String raw = RestClient.builder()
                    .baseUrl(baseUrl)
                    .build()
                    .post()
                    .uri("/api/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            JsonNode root = objectMapper.readTree(raw);
            String text = root.path("message").path("content").asText("").trim();

            if (text.isBlank()) {
                log.warn("OllamaSupportProvider returned empty content for model={}", model);
                return FALLBACK_REPLY;
            }

            log.debug("OllamaSupportProvider success — model={} tokens≈{}", model, text.split("\\s+").length);
            return text;

        } catch (Exception ex) {
            log.error("OllamaSupportProvider error — model={}: {}", model, ex.getMessage());
            return FALLBACK_REPLY;
        }
    }

    @SuppressWarnings("unused")
    public String fallback(String systemPrompt, String userMessage, Throwable t) {
        log.warn("OllamaSupportProvider circuit breaker OPEN — model={}: {}", model, t.getMessage());
        return FALLBACK_REPLY;
    }

    @SuppressWarnings("unused")
    public String fallbackWithHistory(String systemPrompt, List<Map<String, String>> history, String userMessage, Throwable t) {
        log.warn("OllamaSupportProvider circuit breaker OPEN — model={}: {}", model, t.getMessage());
        return FALLBACK_REPLY;
    }
}
