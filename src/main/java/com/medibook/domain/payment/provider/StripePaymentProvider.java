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
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * Stripe payment provider — uses Stripe API v1 with form-encoded requests.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.stripe.enabled", havingValue = "true", matchIfMissing = false)
public class StripePaymentProvider implements PaymentProviderPort {

    private static final String STRIPE_API_BASE = "https://api.stripe.com/v1";

    @Value("${app.payment.stripe.secret-key:#{null}}")
    private String secretKey;

    @Value("${app.payment.stripe.webhook-secret:#{null}}")
    private String webhookSecret;

    private final ObjectMapper objectMapper;

    public StripePaymentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.STRIPE;
    }

    @Override
    @CircuitBreaker(name = "stripeProvider", fallbackMethod = "initiatePaymentFallback")
    public InitiateResult initiatePayment(InitiateRequest request) {
        if (!isConfigured()) {
            log.warn("Stripe secret key not set — dev stub");
            return new InitiateResult("pi_DEV_" + request.idempotencyKey(), null, "requires_payment_method");
        }

        try {
            RestClient client  = buildClient();
            // Stripe amounts in smallest currency unit (cents)
            long amountCents   = request.amount().multiply(BigDecimal.valueOf(100)).longValue();
            String currency    = request.currency().toLowerCase();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("amount",                   String.valueOf(amountCents));
            form.add("currency",                 currency);
            form.add("description",              request.description());
            form.add("metadata[idempotency_key]", request.idempotencyKey());
            form.add("metadata[customer_email]",  request.customerEmail());

            String response = client.post()
                    .uri("/payment_intents")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .header("Idempotency-Key", request.idempotencyKey())
                    .retrieve()
                    .body(String.class);

            JsonNode node   = objectMapper.readTree(response);
            String piId     = node.path("id").asText();
            String clientSecret = node.path("client_secret").asText(null);
            return new InitiateResult(piId, clientSecret, "requires_payment_method");

        } catch (Exception ex) {
            log.error("Stripe initiatePayment failed: {}", ex.getMessage());
            throw new RuntimeException("Stripe payment initiation failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "stripeProvider", fallbackMethod = "verifyPaymentFallback")
    public VerifyResult verifyPayment(String providerRef) {
        if (!isConfigured()) {
            return new VerifyResult(providerRef, "succeeded", BigDecimal.ZERO, "usd");
        }

        try {
            RestClient client = buildClient();
            String response   = client.get()
                    .uri("/payment_intents/{id}", providerRef)
                    .retrieve()
                    .body(String.class);

            JsonNode node   = objectMapper.readTree(response);
            String status   = node.path("status").asText("");
            long amountCents= node.path("amount").asLong(0);
            String currency = node.path("currency").asText("usd");
            BigDecimal amt  = BigDecimal.valueOf(amountCents).divide(BigDecimal.valueOf(100));
            return new VerifyResult(providerRef, status, amt, currency);

        } catch (Exception ex) {
            log.error("Stripe verifyPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Stripe verification failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "stripeProvider", fallbackMethod = "refundPaymentFallback")
    public RefundResult refundPayment(String providerRef, BigDecimal amount, String reason) {
        if (!isConfigured()) {
            return new RefundResult("re_DEV_" + providerRef, true, "Refund (dev)");
        }

        try {
            RestClient client  = buildClient();
            long amountCents   = amount.multiply(BigDecimal.valueOf(100)).longValue();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("payment_intent", providerRef);
            form.add("amount",         String.valueOf(amountCents));
            if (reason != null) form.add("reason", reason);

            String response  = client.post()
                    .uri("/refunds")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode node    = objectMapper.readTree(response);
            String refundRef = node.path("id").asText("re_" + providerRef);
            return new RefundResult(refundRef, true, "Refund created");

        } catch (Exception ex) {
            log.error("Stripe refundPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Stripe refund failed", ex);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) return true;
        try {
            String[] parts = signature.split(",");
            String timestamp = null;
            String sig = null;
            for (String part : parts) {
                if (part.startsWith("t=")) timestamp = part.substring(2);
                if (part.startsWith("v1=")) sig = part.substring(3);
            }
            if (timestamp == null || sig == null) return false;
            String signed = timestamp + "." + payload;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String computed = HexFormat.of().formatHex(mac.doFinal(signed.getBytes(StandardCharsets.UTF_8)));
            return computed.equalsIgnoreCase(sig);
        } catch (Exception e) {
            log.error("Stripe signature verification error", e);
            return false;
        }
    }

    InitiateResult initiatePaymentFallback(InitiateRequest request, Exception ex) {
        log.warn("Stripe circuit open — fallback for idempotencyKey={}", request.idempotencyKey());
        return new InitiateResult("pi_CB_" + request.idempotencyKey(), null, "requires_payment_method");
    }

    VerifyResult verifyPaymentFallback(String ref, Exception ex) {
        return new VerifyResult(ref, "PENDING", BigDecimal.ZERO, "usd");
    }

    RefundResult refundPaymentFallback(String ref, BigDecimal amount, String reason, Exception ex) {
        throw new RuntimeException("Stripe temporarily unavailable. Refund will be retried.", ex);
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl(STRIPE_API_BASE)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + secretKey)
                .build();
    }

    private boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank();
    }
}
