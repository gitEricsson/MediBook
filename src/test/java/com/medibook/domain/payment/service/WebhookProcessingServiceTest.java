package com.medibook.domain.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.payment.entity.*;
import com.medibook.domain.payment.provider.PaymentProviderFactory;
import com.medibook.domain.payment.provider.PaymentProviderPort;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.payment.repository.PaymentWebhookEventRepository;
import com.medibook.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("WebhookProcessingService — Monnify + Security Tests")
class WebhookProcessingServiceTest {

    @Mock PaymentWebhookEventRepository webhookRepository;
    @Mock PaymentRepository             paymentRepository;
    @Mock PaymentProviderFactory        providerFactory;
    @Mock PaymentService                paymentService;

    @InjectMocks WebhookProcessingService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private User        patient;
    private Appointment appointment;
    private Payment     payment;
    private PaymentProviderPort mockMonnifyPort;

    // Monnify SUCCESSFUL_TRANSACTION webhook body
    private static final String MONNIFY_SUCCESS_PAYLOAD = """
            {
              "eventType": "SUCCESSFUL_TRANSACTION",
              "eventData": {
                "transactionReference": "MNFY|2024|000001",
                "paymentReference":    "merchant-ref-001",
                "amountPaid":          5000.00,
                "totalPayable":        5000.00,
                "paymentStatus":       "PAID",
                "currencyCode":        "NGN"
              }
            }
            """;

    private static final String MONNIFY_FAILED_PAYLOAD = """
            {
              "eventType": "FAILED_TRANSACTION",
              "eventData": {
                "transactionReference": "MNFY|2024|000002",
                "paymentReference":    "merchant-ref-002",
                "amountPaid":          0.00,
                "paymentStatus":       "FAILED"
              }
            }
            """;

    private static final String MONNIFY_UNDERPAID_PAYLOAD = """
            {
              "eventType": "PARTIAL_PAYMENT",
              "eventData": {
                "transactionReference": "MNFY|2024|000003",
                "paymentReference":    "merchant-ref-003",
                "amountPaid":          2500.00,
                "paymentStatus":       "PARTIALLY_PAID"
              }
            }
            """;

    @BeforeEach
    void setUp() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", objectMapper);

        patient = User.builder().id(1L).email("p@test.com")
                .firstName("Jane").lastName("Doe").build();

        appointment = Appointment.builder().id(1L).patient(patient)
                .status(AppointmentStatus.PENDING)
                .scheduledAt(LocalDateTime.now().plusDays(1)).build();

        payment = Payment.builder()
                .id(10L)
                .appointment(appointment)
                .patient(patient)
                .idempotencyKey("merchant-ref-001")
                .provider(PaymentProvider.MONNIFY)
                .providerRef("MNFY|2024|000001")
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();

        mockMonnifyPort = mock(PaymentProviderPort.class);
    }

    @Test
    @DisplayName("duplicate webhook is silently ignored — idempotency key check")
    void processWebhook_duplicate_skipsProcessing() {
        when(webhookRepository.existsByIdempotencyKey("idem-001")).thenReturn(true);

        service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "sig", "idem-001");

        verify(providerFactory, never()).get(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("invalid Monnify webhook signature is rejected with 401")
    void processWebhook_invalidSignature_throws401() {
        when(webhookRepository.existsByIdempotencyKey("idem-002")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), eq("bad-sig"))).thenReturn(false);

        assertThatThrownBy(() ->
                service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "bad-sig", "idem-002"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid webhook signature");

        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("unknown provider name returns 400")
    void processWebhook_unknownProvider_throws400() {
        when(webhookRepository.existsByIdempotencyKey("idem-999")).thenReturn(false);

        assertThatThrownBy(() ->
                service.processWebhook("UNKNOWN_PROVIDER", "{}", "", "idem-999"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown provider");
    }

    @Test
    @DisplayName("valid PAID Monnify webhook updates payment status to SUCCESSFUL")
    void processWebhook_monnifyPaid_updatesStatusToSuccessful() {
        when(webhookRepository.existsByIdempotencyKey("idem-003")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentRepository.findByProviderRef("MNFY|2024|000001")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "valid-sig", "idem-003");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.SUCCESSFUL);
    }

    @Test
    @DisplayName("successful payment webhook triggers invoice marking + event publishing exactly once")
    void processWebhook_monnifyPaid_triggersHandleSuccessfulWebhookPayment() {
        when(webhookRepository.existsByIdempotencyKey("idem-004")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentRepository.findByProviderRef("MNFY|2024|000001")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "valid-sig", "idem-004");

        verify(paymentService, times(1)).handleSuccessfulWebhookPayment(payment);
    }

    @Test
    @DisplayName("already-SUCCESSFUL payment does NOT trigger handleSuccessfulWebhookPayment again")
    void processWebhook_alreadySuccessful_noDoubleConfirm() {
        payment.setStatus(PaymentStatus.SUCCESSFUL); // already confirmed

        when(webhookRepository.existsByIdempotencyKey("idem-005")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentRepository.findByProviderRef("MNFY|2024|000001")).thenReturn(Optional.of(payment));

        service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "valid-sig", "idem-005");

        verify(paymentService, never()).handleSuccessfulWebhookPayment(any());
        verify(paymentRepository, never()).save(any());
    }

    @Test
    @DisplayName("amount mismatch (underpaid) — payment status is NOT updated")
    void processWebhook_amountMismatch_doesNotConfirm() {
        // payment expects 5000 but payload says 2500 paid
        when(webhookRepository.existsByIdempotencyKey("idem-006")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);

        Payment underpaidPayment = Payment.builder()
                .id(11L).appointment(appointment).patient(patient)
                .idempotencyKey("merchant-ref-003")
                .provider(PaymentProvider.MONNIFY)
                .providerRef("MNFY|2024|000003")
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();

        when(paymentRepository.findByProviderRef("MNFY|2024|000003")).thenReturn(Optional.of(underpaidPayment));

        service.processWebhook("monnify", MONNIFY_UNDERPAID_PAYLOAD, "valid-sig", "idem-006");

        verify(paymentRepository, never()).save(any());
        verify(paymentService, never()).handleSuccessfulWebhookPayment(any());
    }

    @Test
    @DisplayName("FAILED Monnify webhook updates payment status to FAILED")
    void processWebhook_monnifyFailed_updatesStatusToFailed() {
        Payment pendingPayment = Payment.builder()
                .id(12L).appointment(appointment).patient(patient)
                .idempotencyKey("merchant-ref-002")
                .provider(PaymentProvider.MONNIFY)
                .providerRef("MNFY|2024|000002")
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();

        when(webhookRepository.existsByIdempotencyKey("idem-007")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentRepository.findByProviderRef("MNFY|2024|000002")).thenReturn(Optional.of(pendingPayment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processWebhook("monnify", MONNIFY_FAILED_PAYLOAD, "valid-sig", "idem-007");

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        verify(paymentService, never()).handleSuccessfulWebhookPayment(any());
    }

    @Test
    @DisplayName("webhook event is persisted with processed=true on success")
    void processWebhook_success_persistsEventAsProcessed() {
        when(webhookRepository.existsByIdempotencyKey("idem-008")).thenReturn(false);
        when(providerFactory.get(PaymentProvider.MONNIFY)).thenReturn(mockMonnifyPort);
        when(mockMonnifyPort.verifyWebhookSignature(any(), any())).thenReturn(true);
        when(paymentRepository.findByProviderRef("MNFY|2024|000001")).thenReturn(Optional.of(payment));
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(webhookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.processWebhook("monnify", MONNIFY_SUCCESS_PAYLOAD, "valid-sig", "idem-008");

        ArgumentCaptor<PaymentWebhookEvent> captor = ArgumentCaptor.forClass(PaymentWebhookEvent.class);
        verify(webhookRepository).save(captor.capture());
        PaymentWebhookEvent saved = captor.getValue();
        assertThat(saved.isProcessed()).isTrue();
        assertThat(saved.getProcessedAt()).isNotNull();
        assertThat(saved.getProvider()).isEqualTo("MONNIFY");
        assertThat(saved.getIdempotencyKey()).isEqualTo("idem-008");
    }
}
