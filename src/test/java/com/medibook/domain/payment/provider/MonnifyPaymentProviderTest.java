package com.medibook.domain.payment.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.payment.entity.PaymentProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MonnifyPaymentProvider — Unit Tests")
class MonnifyPaymentProviderTest {

    @Mock MonnifyTokenManager tokenManager;

    @InjectMocks MonnifyPaymentProvider provider;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(provider, "objectMapper",     objectMapper);
        ReflectionTestUtils.setField(provider, "secretKey",        "test-secret-key");
        ReflectionTestUtils.setField(provider, "contractCode",     "TEST_CONTRACT_CODE");
        ReflectionTestUtils.setField(provider, "baseUrl",          "https://sandbox.monnify.com");
        ReflectionTestUtils.setField(provider, "defaultRedirectUrl", "http://localhost:3000/callback");
    }

    @Test
    @DisplayName("getProvider — returns MONNIFY")
    void getProvider_returnsMonnify() {
        assertThat(provider.getProvider()).isEqualTo(PaymentProvider.MONNIFY);
    }

    // ─── Webhook signature ───────────────────────────────────────────────────

    @Test
    @DisplayName("verifyWebhookSignature — valid SHA-512(secretKey + body) returns true")
    void verifyWebhookSignature_valid_returnsTrue() throws Exception {
        String secret  = "test-secret-key";
        String body    = "{\"eventType\":\"SUCCESSFUL_TRANSACTION\"}";
        String input   = secret + body;
        byte[] hash    = MessageDigest.getInstance("SHA-512").digest(input.getBytes(StandardCharsets.UTF_8));
        String sig     = HexFormat.of().formatHex(hash);

        assertThat(provider.verifyWebhookSignature(body, sig)).isTrue();
    }

    @Test
    @DisplayName("verifyWebhookSignature — wrong signature returns false")
    void verifyWebhookSignature_wrongSignature_returnsFalse() {
        assertThat(provider.verifyWebhookSignature("{}", "deadbeef")).isFalse();
    }

    @Test
    @DisplayName("verifyWebhookSignature — blank signature is rejected")
    void verifyWebhookSignature_blankSignature_returnsFalse() {
        assertThat(provider.verifyWebhookSignature("{}", "")).isFalse();
        assertThat(provider.verifyWebhookSignature("{}", null)).isFalse();
    }

    @Test
    @DisplayName("verifyWebhookSignature — unconfigured provider accepts any signature (dev mode)")
    void verifyWebhookSignature_notConfigured_acceptsAll() {
        ReflectionTestUtils.setField(provider, "secretKey", null);
        assertThat(provider.verifyWebhookSignature("{}", "any-sig")).isTrue();
    }

    // ─── InitiatePayment (dev stub path) ────────────────────────────────────

    @Test
    @DisplayName("initiatePayment — returns dev stub when not configured")
    void initiatePayment_notConfigured_returnsDevStub() {
        ReflectionTestUtils.setField(provider, "secretKey",    null);
        ReflectionTestUtils.setField(provider, "contractCode", null);

        PaymentProviderPort.InitiateRequest req = new PaymentProviderPort.InitiateRequest(
                "key-001", "test@test.com", "Jane Doe",
                BigDecimal.valueOf(5000), "NGN", "Consultation fee", null);

        PaymentProviderPort.InitiateResult result = provider.initiatePayment(req);

        assertThat(result.providerRef()).startsWith("MN-DEV-");
        assertThat(result.authorizationUrl()).contains("sandbox.monnify.com");
        assertThat(result.status()).isEqualTo("PENDING");
    }

    // ─── VerifyPayment (dev stub path) ──────────────────────────────────────

    @Test
    @DisplayName("verifyPayment — returns dev stub when not configured")
    void verifyPayment_notConfigured_returnsDevStub() {
        ReflectionTestUtils.setField(provider, "secretKey",    null);
        ReflectionTestUtils.setField(provider, "contractCode", null);

        PaymentProviderPort.VerifyResult result = provider.verifyPayment("MN-TXN-001");

        assertThat(result.providerRef()).isEqualTo("MN-TXN-001");
        assertThat(result.status()).isEqualTo("PAID");
    }

    // ─── RefundPayment (dev stub path) ──────────────────────────────────────

    @Test
    @DisplayName("refundPayment — returns dev stub when not configured")
    void refundPayment_notConfigured_returnsDevStub() {
        ReflectionTestUtils.setField(provider, "secretKey",    null);
        ReflectionTestUtils.setField(provider, "contractCode", null);

        PaymentProviderPort.RefundResult result =
                provider.refundPayment("MN-TXN-001", BigDecimal.valueOf(5000), "Patient request");

        assertThat(result.success()).isTrue();
        assertThat(result.message()).contains("dev stub");
    }

    // ─── Circuit-breaker fallbacks ───────────────────────────────────────────

    @Test
    @DisplayName("initiatePaymentFallback — returns CB placeholder with PENDING status")
    void initiatePaymentFallback_returnsCbPlaceholder() {
        PaymentProviderPort.InitiateRequest req = new PaymentProviderPort.InitiateRequest(
                "key-cb", "t@t.com", "Test", BigDecimal.ONE, "NGN", "desc", null);

        PaymentProviderPort.InitiateResult result =
                provider.initiatePaymentFallback(req, new RuntimeException("circuit open"));

        assertThat(result.providerRef()).startsWith("MN-CB-");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.authorizationUrl()).isNull();
    }

    @Test
    @DisplayName("verifyPaymentFallback — returns PENDING without crashing")
    void verifyPaymentFallback_returnsPending() {
        PaymentProviderPort.VerifyResult result =
                provider.verifyPaymentFallback("MN-TXN-001", new RuntimeException("circuit open"));

        assertThat(result.status()).isEqualTo("PENDING");
        assertThat(result.amountPaid()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("refundPaymentFallback — throws RuntimeException so caller knows refund is pending")
    void refundPaymentFallback_throwsRuntimeException() {
        assertThatThrownBy(() ->
                provider.refundPaymentFallback(
                        "MN-TXN-001", BigDecimal.TEN, "reason", new RuntimeException("circuit open")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("temporarily unavailable");
    }
}
