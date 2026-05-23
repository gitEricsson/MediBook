package com.medibook.domain.payment.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.payment.dto.InitiatePaymentRequest;
import com.medibook.domain.payment.dto.PaymentResponse;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentProvider;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.provider.PaymentProviderFactory;
import com.medibook.domain.payment.provider.PaymentProviderPort;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.common.sequence.SequenceService;
import com.medibook.messaging.outbox.OutboxEventRepository;
import com.medibook.security.UserPrincipal;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository          paymentRepository;
    @Mock InvoiceRepository          invoiceRepository;
    @Mock AppointmentRepository      appointmentRepository;
    @Mock PaymentProviderFactory     providerFactory;
    @Mock OutboxEventRepository      outboxRepository;
    @Mock ObjectMapper               objectMapper;
    @Mock SequenceService            sequenceService;
    @Mock com.medibook.config.HospitalProperties hospitalProperties;
    @Mock PricingEngine              pricingEngine;
    @Mock MeterRegistry              meterRegistry;
    @Mock Counter                    counter;

    @InjectMocks
    PaymentService paymentService;

    private User patient;
    private Doctor doctor;
    private Appointment appointment;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        patient = User.builder()
                .id(1L)
                .email("patient@test.com")
                .firstName("Jane")
                .lastName("Doe")
                .role(Role.ROLE_PATIENT)
                .build();

        Department dept = Department.builder().id(1L).name("Cardiology").build();

        User doctorUser = User.builder().id(2L).firstName("Dr").lastName("Smith").email("doc@test.com").build();
        doctor = Doctor.builder()
                .id(1L)
                .user(doctorUser)
                .department(dept)
                .licenseNumber("LIC-001")
                .consultationFee(BigDecimal.valueOf(5000))
                .yearsOfExperience(5)
                .build();

        appointment = Appointment.builder()
                .id(1L)
                .patient(patient)
                .doctor(doctor)
                .department(dept)
                .scheduledAt(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusMinutes(30))
                .durationMins(30)
                .status(AppointmentStatus.PENDING)
                .type(AppointmentType.IN_PERSON)
                .confirmationCode("MB-TEST01")
                .build();

        principal = UserPrincipal.fromUser(patient);

        lenient().when(hospitalProperties.getFeeForDoctor(any(), anyInt()))
                .thenReturn(BigDecimal.valueOf(5000));
        lenient().when(pricingEngine.calculate(any(), any(), any()))
                .thenReturn(BigDecimal.valueOf(5000));
        lenient().when(meterRegistry.counter(anyString(), any(String[].class)))
                .thenReturn(counter);
    }

    @Test
    void initiatePayment_success() throws Exception {
        InitiatePaymentRequest req = new InitiatePaymentRequest();
        req.setAppointmentId(1L);
        req.setProvider(PaymentProvider.PAYSTACK);
        req.setAmount(BigDecimal.valueOf(5000));
        req.setIdempotencyKey("key-001");

        when(paymentRepository.findByIdempotencyKey("key-001")).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(appointment));
        when(paymentRepository.existsByAppointmentIdAndStatusIn(eq(1L), anyList())).thenReturn(false);

        PaymentProviderPort mockPort = mock(PaymentProviderPort.class);
        when(mockPort.initiatePayment(any())).thenReturn(
                new PaymentProviderPort.InitiateResult("PS-REF-001", "https://pay.link", "PENDING"));
        when(providerFactory.get(PaymentProvider.PAYSTACK)).thenReturn(mockPort);

        Payment savedPayment = Payment.builder()
                .id(1L)
                .appointment(appointment)
                .patient(patient)
                .idempotencyKey("key-001")
                .provider(PaymentProvider.PAYSTACK)
                .providerRef("PS-REF-001")
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.save(any())).thenReturn(savedPayment);
        when(invoiceRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        when(outboxRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentResponse response = paymentService.initiatePayment(req, principal);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(response.getProviderRef()).isEqualTo("PS-REF-001");
        verify(paymentRepository).save(any());
    }

    @Test
    void initiatePayment_idempotent_returnsExisting() throws Exception {
        InitiatePaymentRequest req = new InitiatePaymentRequest();
        req.setAppointmentId(1L);
        req.setProvider(PaymentProvider.PAYSTACK);
        req.setAmount(BigDecimal.valueOf(5000));
        req.setIdempotencyKey("key-dup");

        Payment existing = Payment.builder()
                .id(1L)
                .appointment(appointment)
                .patient(patient)
                .idempotencyKey("key-dup")
                .provider(PaymentProvider.PAYSTACK)
                .providerRef("PS-REF-DUP")
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();
        when(paymentRepository.findByIdempotencyKey("key-dup")).thenReturn(Optional.of(existing));

        PaymentResponse response = paymentService.initiatePayment(req, principal);

        assertThat(response.getProviderRef()).isEqualTo("PS-REF-DUP");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiatePayment_cancelledAppointment_throwsException() {
        appointment.setStatus(AppointmentStatus.CANCELLED);

        InitiatePaymentRequest req = new InitiatePaymentRequest();
        req.setAppointmentId(1L);
        req.setProvider(PaymentProvider.PAYSTACK);
        req.setAmount(BigDecimal.valueOf(5000));

        when(paymentRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> paymentService.initiatePayment(req, principal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void refundPayment_alreadyRefunded_throwsException() {
        Payment payment = Payment.builder()
                .id(1L)
                .appointment(appointment)
                .patient(patient)
                .provider(PaymentProvider.PAYSTACK)
                .amount(BigDecimal.valueOf(5000))
                .status(PaymentStatus.REFUNDED)
                .refundedAt(LocalDateTime.now())
                .build();

        when(paymentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(payment));

        PaymentResponse response = paymentService.refundPayment(1L, null, null, principal);

        assertThat(response.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        verifyNoInteractions(providerFactory);
    }
}
