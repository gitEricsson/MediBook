package com.medibook.domain.payment.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.payment.entity.PaymentProvider;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * Flutterwave (Rave v3) payment provider — wires real HTTP calls against api.flutterwave.com/v3.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.flutterwave.enabled", havingValue = "true", matchIfMissing = false)
public class FlutterwavePaymentProvider implements PaymentProviderPort {

    @Value("${app.payment.flutterwave.secret-key:#{null}}")
    private String secretKey;

    @Value("${app.payment.flutterwave.base-url:https://api.flutterwave.com/v3}")
    private String baseUrl;

    @Value("${app.payment.flutterwave.redirect-url:https://medibook.io/payment/callback}")
    private String redirectUrl;

    private final ObjectMapper objectMapper;

    public FlutterwavePaymentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.FLUTTERWAVE;
    }

    @Override
    @CircuitBreaker(name = "flutterwaveProvider", fallbackMethod = "initiatePaymentFallback")
    public InitiateResult initiatePayment(InitiateRequest request) {
        if (!isConfigured()) {
            log.warn("Flutterwave secret key not set — dev stub");
            return new InitiateResult("FW-DEV-" + request.idempotencyKey(), null, "PENDING");
        }

        try {
            RestClient client = buildClient();
            String body = objectMapper.writeValueAsString(Map.of(
                    "tx_ref",        request.idempotencyKey(),
                    "amount",        request.amount(),
                    "currency",      request.currency(),
                    "redirect_url",  redirectUrl,
                    "customer",      Map.of(
                            "email", request.customerEmail(),
                            "name",  request.customerName()),
                    "customizations", Map.of("title", "MediBook Consultation")
            ));

            String response = client.post()
                    .uri("/payments")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node  = objectMapper.readTree(response);
            String payLink = node.path("data").path("link").asText(null);
            String ref     = request.idempotencyKey();
            return new InitiateResult(ref, payLink, "PENDING");

        } catch (Exception ex) {
            log.error("Flutterwave initiatePayment failed: {}", ex.getMessage());
            throw new RuntimeException("Flutterwave payment initiation failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "flutterwaveProvider", fallbackMethod = "verifyPaymentFallback")
    public VerifyResult verifyPayment(String providerRef) {
        if (!isConfigured()) {
            return new VerifyResult(providerRef, "successful", BigDecimal.ZERO, "NGN");
        }

        try {
            RestClient client  = buildClient();
            String response    = client.get()
                    .uri("/transactions/{id}/verify", providerRef)
                    .retrieve()
                    .body(String.class);

            JsonNode node   = objectMapper.readTree(response);
            String status   = node.path("data").path("status").asText("");
            BigDecimal amt  = BigDecimal.valueOf(node.path("data").path("amount").asDouble(0));
            String currency = node.path("data").path("currency").asText("NGN");
            return new VerifyResult(providerRef, status, amt, currency);

        } catch (Exception ex) {
            log.error("Flutterwave verifyPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Flutterwave verification failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "flutterwaveProvider", fallbackMethod = "refundPaymentFallback")
    public RefundResult refundPayment(String providerRef, BigDecimal amount, String reason) {
        if (!isConfigured()) {
            return new RefundResult("FW-REFUND-" + providerRef, true, "Refund (dev)");
        }

        try {
            RestClient client = buildClient();
            String body = objectMapper.writeValueAsString(Map.of("amount", amount));
            String response = client.post()
                    .uri("/transactions/{id}/refund", providerRef)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node    = objectMapper.readTree(response);
            String refundRef = node.path("data").path("id").asText("FW-REFUND-" + UUID.randomUUID());
            return new RefundResult(refundRef, true, "Refund initiated");

        } catch (Exception ex) {
            log.error("Flutterwave refundPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Flutterwave refund failed", ex);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (!isConfigured()) return true;
        return secretKey.equals(signature);
    }

    InitiateResult initiatePaymentFallback(InitiateRequest request, Exception ex) {
        log.warn("Flutterwave circuit open for idempotencyKey={}", request.idempotencyKey());
        return new InitiateResult("FW-CB-" + request.idempotencyKey(), null, "PENDING");
    }

    VerifyResult verifyPaymentFallback(String ref, Exception ex) {
        return new VerifyResult(ref, "PENDING", BigDecimal.ZERO, "NGN");
    }

    RefundResult refundPaymentFallback(String ref, BigDecimal amount, String reason, Exception ex) {
        throw new RuntimeException("Flutterwave temporarily unavailable. Refund will be retried.", ex);
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
