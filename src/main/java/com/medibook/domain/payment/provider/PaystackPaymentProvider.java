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
import org.springframework.web.client.RestClientException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;

/**
 * Paystack payment provider — wires real HTTP calls against api.paystack.co.
 * Falls back gracefully when secret key is absent (local dev).
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.paystack.enabled", havingValue = "true", matchIfMissing = true)
public class PaystackPaymentProvider implements PaymentProviderPort {

    @Value("${app.payment.paystack.secret-key:#{null}}")
    private String secretKey;

    @Value("${app.payment.paystack.base-url:https://api.paystack.co}")
    private String baseUrl;

    private final ObjectMapper objectMapper;

    public PaystackPaymentProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.PAYSTACK;
    }

    @Override
    @CircuitBreaker(name = "paystackProvider", fallbackMethod = "initiatePaymentFallback")
    public InitiateResult initiatePayment(InitiateRequest request) {
        if (!isConfigured()) {
            return devStub(request.idempotencyKey());
        }

        try {
            RestClient client = buildClient();
            // Paystack amount is in kobo (smallest unit) — multiply by 100
            long amountInKobo = request.amount().multiply(BigDecimal.valueOf(100)).longValue();

            // Paystack rejects emails with non-public TLDs like .local, .test, .invalid, .localhost.
            // For dev/test users we substitute with a synthetic public-TLD address derived from
            // the original local part so the gateway accepts the request.
            String email = sanitizeEmailForGateway(request.customerEmail());

            String body = objectMapper.writeValueAsString(Map.of(
                    "email",      email,
                    "amount",     amountInKobo,
                    "currency",   request.currency(),
                    "reference",  request.idempotencyKey(),
                    "metadata",   Map.of(
                            "description", request.description(),
                            "customer",    request.customerName(),
                            "originalEmail", request.customerEmail() == null ? "" : request.customerEmail()),
                    "callback_url", request.callbackUrl() != null ? request.callbackUrl() : ""
            ));

            String response = client.post()
                    .uri("/transaction/initialize")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            String ref         = node.path("data").path("reference").asText();
            String authUrl     = node.path("data").path("authorization_url").asText(null);
            return new InitiateResult(ref, authUrl, "PENDING");

        } catch (RestClientException ex) {
            log.error("Paystack initiatePayment failed: {}", ex.getMessage());
            throw new RuntimeException("Paystack payment initiation failed", ex);
        } catch (Exception ex) {
            log.error("Paystack serialization error: {}", ex.getMessage());
            throw new RuntimeException("Paystack payment initiation error", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "paystackProvider", fallbackMethod = "verifyPaymentFallback")
    public VerifyResult verifyPayment(String providerRef) {
        if (!isConfigured()) {
            return new VerifyResult(providerRef, "success", BigDecimal.ZERO, "NGN");
        }

        try {
            RestClient client = buildClient();
            String response = client.get()
                    .uri("/transaction/verify/{reference}", providerRef)
                    .retrieve()
                    .body(String.class);

            JsonNode node      = objectMapper.readTree(response);
            String status      = node.path("data").path("status").asText("");
            long   amountKobo  = node.path("data").path("amount").asLong(0);
            String currency    = node.path("data").path("currency").asText("NGN");
            BigDecimal amount  = BigDecimal.valueOf(amountKobo).divide(BigDecimal.valueOf(100));

            return new VerifyResult(providerRef, status, amount, currency);

        } catch (Exception ex) {
            log.error("Paystack verifyPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Paystack verification failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "paystackProvider", fallbackMethod = "refundPaymentFallback")
    public RefundResult refundPayment(String providerRef, BigDecimal amount, String reason) {
        if (!isConfigured()) {
            return new RefundResult("PS-REFUND-" + providerRef, true, "Refund initiated (dev)");
        }

        try {
            RestClient client = buildClient();
            long amountKobo   = amount.multiply(BigDecimal.valueOf(100)).longValue();

            String body = objectMapper.writeValueAsString(Map.of(
                    "transaction", providerRef,
                    "amount",      amountKobo
            ));

            String response = client.post()
                    .uri("/refund")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node    = objectMapper.readTree(response);
            String refundRef = node.path("data").path("id").asText("PS-REFUND-" + providerRef);
            return new RefundResult(refundRef, true, "Refund initiated");

        } catch (Exception ex) {
            log.error("Paystack refundPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Paystack refund failed", ex);
        }
    }

    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (!isConfigured()) return false;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] computed    = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String computedHex = HexFormat.of().formatHex(computed);
            return computedHex.equalsIgnoreCase(signature);
        } catch (Exception e) {
            log.error("Paystack signature verification error", e);
            return false;
        }
    }

    // ── Circuit breaker fallbacks ──────────────────────────────────────────

    InitiateResult initiatePaymentFallback(InitiateRequest request, Exception ex) {
        log.warn("Paystack circuit open — using fallback for idempotencyKey={}", request.idempotencyKey());
        return new InitiateResult("PS-CB-" + request.idempotencyKey(), null, "PENDING");
    }

    VerifyResult verifyPaymentFallback(String providerRef, Exception ex) {
        log.warn("Paystack circuit open — verify fallback for ref={}", providerRef);
        return new VerifyResult(providerRef, "PENDING", BigDecimal.ZERO, "NGN");
    }

    RefundResult refundPaymentFallback(String providerRef, BigDecimal amount, String reason, Exception ex) {
        log.warn("Paystack circuit open — refund fallback for ref={}", providerRef);
        throw new RuntimeException("Payment provider temporarily unavailable. Refund will be retried.", ex);
    }

    // ── Helpers ────────────────────────────────────────────────────────────

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

    /**
     * Paystack's email validator rejects non-public TLDs (.local, .test, .invalid, .localhost,
     * .example, .lan, .home, .corp). For dev/staging users with such emails, substitute a
     * synthetic public-TLD address that's deterministic per source email so refund/lookup
     * keyed by email still works. The original email is preserved in the request metadata.
     */
    static String sanitizeEmailForGateway(String email) {
        if (email == null || email.isBlank()) {
            return "noreply+anonymous@medibook.test.com";
        }
        String lower = email.trim().toLowerCase();
        int at = lower.lastIndexOf('@');
        if (at < 0) {
            return "noreply+invalid@medibook.test.com";
        }
        String localPart = lower.substring(0, at);
        String domain = lower.substring(at + 1);
        int lastDot = domain.lastIndexOf('.');
        String tld = lastDot >= 0 ? domain.substring(lastDot + 1) : domain;
        // Whitelist of TLDs Paystack rejects; substitute with a real public TLD.
        switch (tld) {
            case "local":
            case "localhost":
            case "test":
            case "invalid":
            case "example":
            case "lan":
            case "home":
            case "corp":
            case "internal":
                return localPart + "@medibook-test.com";
            default:
                return email;
        }
    }

    private InitiateResult devStub(String idempotencyKey) {
        log.warn("Paystack secret key not set — returning dev stub for key={}", idempotencyKey);
        return new InitiateResult("PS-DEV-" + idempotencyKey, null, "PENDING");
    }
}
