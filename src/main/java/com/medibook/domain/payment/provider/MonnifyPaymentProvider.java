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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Monnify payment provider — implements PaymentProviderPort for Nigerian payments.
 *
 * Authentication: Monnify uses short-lived Bearer tokens obtained via Basic Auth.
 *   Token lifecycle is managed by MonnifyTokenManager.
 *
 * Amount: Monnify uses NGN amounts as-is (not smallest unit — no kobo conversion needed).
 *
 * Webhook signature: SHA-512 of (secretKey + rawBody), hex-encoded.
 *   Monnify sends this in the "monnify-signature" header.
 *
 * Sandbox: https://sandbox.monnify.com
 * Live:    https://api.monnify.com
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.payment.monnify.enabled", havingValue = "true")
public class MonnifyPaymentProvider implements PaymentProviderPort {

    @Value("${app.payment.monnify.secret-key:#{null}}")
    private String secretKey;

    @Value("${app.payment.monnify.contract-code:#{null}}")
    private String contractCode;

    @Value("${app.payment.monnify.base-url:https://sandbox.monnify.com}")
    private String baseUrl;

    @Value("${app.payment.monnify.redirect-url:#{null}}")
    private String defaultRedirectUrl;

    private final MonnifyTokenManager tokenManager;
    private final ObjectMapper        objectMapper;

    public MonnifyPaymentProvider(MonnifyTokenManager tokenManager, ObjectMapper objectMapper) {
        this.tokenManager = tokenManager;
        this.objectMapper = objectMapper;
    }

    @Override
    public PaymentProvider getProvider() {
        return PaymentProvider.MONNIFY;
    }

    // ─── Initialize ─────────────────────────────────────────────────────────

    @Override
    @CircuitBreaker(name = "monnifyProvider", fallbackMethod = "initiatePaymentFallback")
    public InitiateResult initiatePayment(InitiateRequest request) {
        if (!isConfigured()) {
            return devStub(request.idempotencyKey());
        }

        try {
            String redirectUrl = request.callbackUrl() != null && !request.callbackUrl().isBlank()
                    ? request.callbackUrl()
                    : defaultRedirectUrl;

            String body = objectMapper.writeValueAsString(Map.of(
                    "amount",             request.amount(),
                    "customerName",       request.customerName(),
                    "customerEmail",      request.customerEmail(),
                    "paymentReference",   request.idempotencyKey(),
                    "paymentDescription", request.description(),
                    "currencyCode",       request.currency(),
                    "contractCode",       contractCode,
                    "redirectUrl",        redirectUrl != null ? redirectUrl : "",
                    "paymentMethods",     List.of("CARD", "ACCOUNT_TRANSFER", "USSD")
            ));

            String response = buildClient()
                    .post()
                    .uri("/api/v1/merchant/transactions/init-transaction")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);

            if (!node.path("requestSuccessful").asBoolean(false)) {
                String msg = node.path("responseMessage").asText("Unknown error");
                log.error("Monnify init-transaction rejected: {}", msg);
                throw new RuntimeException("Monnify rejected payment initialization: " + msg);
            }

            String transactionRef = node.path("responseBody").path("transactionReference").asText();
            String checkoutUrl    = node.path("responseBody").path("checkoutUrl").asText(null);

            log.info("Monnify transaction initialized: ref={}", transactionRef);
            return new InitiateResult(transactionRef, checkoutUrl, "PENDING");

        } catch (RestClientException ex) {
            log.error("Monnify initiatePayment HTTP error: {}", ex.getMessage());
            throw new RuntimeException("Monnify payment initiation failed", ex);
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            log.error("Monnify initiatePayment error: {}", ex.getMessage());
            throw new RuntimeException("Monnify payment initiation error", ex);
        }
    }

    // ─── Verify ─────────────────────────────────────────────────────────────

    /**
     * Verifies a transaction using Monnify's transaction-reference endpoint.
     * providerRef here is the Monnify transactionReference stored at init time.
     */
    @Override
    @CircuitBreaker(name = "monnifyProvider", fallbackMethod = "verifyPaymentFallback")
    public VerifyResult verifyPayment(String providerRef) {
        if (!isConfigured()) {
            return new VerifyResult(providerRef, "PAID", BigDecimal.ZERO, "NGN");
        }

        try {
            String response = buildClient()
                    .get()
                    .uri("/api/v2/transactions/{transactionReference}", providerRef)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);

            if (!node.path("requestSuccessful").asBoolean(false)) {
                String msg = node.path("responseMessage").asText("Unknown error");
                log.warn("Monnify verification rejected: ref={} message={}", providerRef, msg);
                return new VerifyResult(providerRef, "FAILED", BigDecimal.ZERO, "NGN");
            }

            JsonNode body      = node.path("responseBody");
            String   status    = body.path("paymentStatus").asText("PENDING");
            double   paidAmt   = body.path("amountPaid").asDouble(0);
            String   currency  = body.path("currencyCode").asText("NGN");

            BigDecimal amountPaid = BigDecimal.valueOf(paidAmt);
            log.debug("Monnify verify: ref={} status={} amountPaid={}", providerRef, status, amountPaid);
            return new VerifyResult(providerRef, status, amountPaid, currency);

        } catch (Exception ex) {
            log.error("Monnify verifyPayment failed: ref={} error={}", providerRef, ex.getMessage());
            throw new RuntimeException("Monnify verification failed", ex);
        }
    }

    // ─── Refund ─────────────────────────────────────────────────────────────

    /**
     * Monnify refunds require a pre-configured disbursement account and separate
     * KYC approval. This implementation attempts the API call and falls back to a
     * clear error so that refund staff can action it via the Monnify dashboard.
     */
    @Override
    @CircuitBreaker(name = "monnifyProvider", fallbackMethod = "refundPaymentFallback")
    public RefundResult refundPayment(String providerRef, BigDecimal amount, String reason) {
        if (!isConfigured()) {
            return new RefundResult("MN-REFUND-" + providerRef, true, "Refund initiated (dev stub)");
        }

        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "transactionReference", providerRef,
                    "refundReason",         reason != null ? reason : "Patient request",
                    "refundAmount",         amount,
                    "destinationAccountNumber", "",   // populated by Monnify from original payment
                    "destinationAccountBankCode", ""
            ));

            String response = buildClient()
                    .post()
                    .uri("/api/v1/refunds/initiate-refund")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node   = objectMapper.readTree(response);
            boolean  ok     = node.path("requestSuccessful").asBoolean(false);
            String   refRef = node.path("responseBody").path("refundReference").asText(
                    "MN-REFUND-" + providerRef);

            if (!ok) {
                String msg = node.path("responseMessage").asText("Refund failed");
                log.warn("Monnify refund rejected: ref={} message={}", providerRef, msg);
                return new RefundResult(null, false, msg);
            }

            log.info("Monnify refund initiated: transactionRef={} refundRef={}", providerRef, refRef);
            return new RefundResult(refRef, true, "Refund initiated");

        } catch (Exception ex) {
            log.error("Monnify refundPayment failed: {}", ex.getMessage());
            throw new RuntimeException("Monnify refund failed — please process via Monnify dashboard", ex);
        }
    }

    // ─── Webhook signature ───────────────────────────────────────────────────

    /**
     * Monnify webhook signature: SHA-512 of (secretKey + rawBody), hex-encoded.
     * The computed hash must equal the "monnify-signature" header (case-insensitive).
     */
    @Override
    public boolean verifyWebhookSignature(String payload, String signature) {
        if (!isConfigured()) {
            return false; // reject unverified webhooks by default
        }
        if (signature == null || signature.isBlank()) {
            log.warn("Monnify webhook arrived with empty signature — rejecting");
            return false;
        }
        try {
            String  input    = secretKey + payload;
            byte[]  hashBytes = MessageDigest.getInstance("SHA-512")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            String  computed  = HexFormat.of().formatHex(hashBytes);
            boolean valid     = computed.equalsIgnoreCase(signature);
            if (!valid) {
                log.warn("Monnify webhook signature mismatch");
            }
            return valid;
        } catch (Exception e) {
            log.error("Monnify webhook signature verification error", e);
            return false;
        }
    }

    // ─── Circuit-breaker fallbacks ───────────────────────────────────────────

    InitiateResult initiatePaymentFallback(InitiateRequest request, Exception ex) {
        log.warn("Monnify circuit open — fallback for idempotencyKey={}", request.idempotencyKey());
        return new InitiateResult("MN-CB-" + request.idempotencyKey(), null, "PENDING");
    }

    VerifyResult verifyPaymentFallback(String providerRef, Exception ex) {
        log.warn("Monnify circuit open — verify fallback for ref={}", providerRef);
        return new VerifyResult(providerRef, "PENDING", BigDecimal.ZERO, "NGN");
    }

    RefundResult refundPaymentFallback(String providerRef, BigDecimal amount, String reason, Exception ex) {
        log.warn("Monnify circuit open — refund fallback for ref={}", providerRef);
        throw new RuntimeException("Payment provider temporarily unavailable. Refund will be retried.", ex);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokenManager.getAccessToken())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean isConfigured() {
        return secretKey != null && !secretKey.isBlank()
                && contractCode != null && !contractCode.isBlank();
    }

    private InitiateResult devStub(String idempotencyKey) {
        log.warn("Monnify not fully configured — returning dev stub for key={}", idempotencyKey);
        return new InitiateResult(
                "MN-DEV-" + idempotencyKey,
                "https://sandbox.monnify.com/checkout/MN-DEV-" + idempotencyKey,
                "PENDING");
    }
}
