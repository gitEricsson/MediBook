package com.medibook.domain.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Manages the short-lived Monnify Bearer access token obtained via Basic Auth.
 *
 * Monnify tokens expire after 3 600 s (1 h). This manager refreshes the token
 * 60 s before expiry and uses double-checked locking to avoid thundering herds.
 * The token is never logged.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.monnify.enabled", havingValue = "true")
@RequiredArgsConstructor
public class MonnifyTokenManager {

    @Value("${app.payment.monnify.api-key:#{null}}")
    private String apiKey;

    @Value("${app.payment.monnify.secret-key:#{null}}")
    private String secretKey;

    @Value("${app.payment.monnify.base-url:https://sandbox.monnify.com}")
    private String baseUrl;

    private final ObjectMapper objectMapper;

    private volatile String  cachedToken  = null;
    private volatile Instant tokenExpiry  = Instant.EPOCH;
    private final    Object  refreshLock  = new Object();

    public String getAccessToken() {
        if (isTokenValid()) {
            return cachedToken;
        }
        return refreshToken();
    }

    private String refreshToken() {
        synchronized (refreshLock) {
            if (isTokenValid()) {
                return cachedToken; // another thread already refreshed
            }

            if (!isConfigured()) {
                log.warn("Monnify credentials not set — returning dev-stub token");
                cachedToken = "dev-stub-token";
                tokenExpiry = Instant.now().plusSeconds(3600);
                return cachedToken;
            }

            try {
                String credentials = Base64.getEncoder().encodeToString(
                        (apiKey + ":" + secretKey).getBytes(StandardCharsets.UTF_8));

                String response = RestClient.create()
                        .post()
                        .uri(baseUrl + "/api/v1/auth/login")
                        .header(HttpHeaders.AUTHORIZATION, "Basic " + credentials)
                        .retrieve()
                        .body(String.class);

                JsonNode body      = objectMapper.readTree(response);
                String   token     = body.path("responseBody").path("accessToken").asText();
                long     expiresIn = body.path("responseBody").path("expiresIn").asLong(3600);

                if (token.isBlank()) {
                    throw new IllegalStateException("Monnify returned empty access token");
                }

                cachedToken = token;
                tokenExpiry = Instant.now().plusSeconds(expiresIn);
                log.info("Monnify access token refreshed; expiresIn={}s", expiresIn);
                return cachedToken;

            } catch (Exception e) {
                log.error("Monnify token refresh failed: {}", e.getMessage());
                throw new RuntimeException("Monnify authentication failed", e);
            }
        }
    }

    private boolean isTokenValid() {
        return cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(60));
    }

    private boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank()
                && secretKey != null && !secretKey.isBlank();
    }
}
