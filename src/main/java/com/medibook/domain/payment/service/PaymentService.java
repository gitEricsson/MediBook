package com.medibook.domain.payment.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.payment.dto.InitiatePaymentRequest;
import com.medibook.domain.payment.dto.InvoiceResponse;
import com.medibook.domain.payment.dto.PaymentResponse;
import com.medibook.domain.payment.entity.*;
import com.medibook.domain.payment.provider.PaymentProviderFactory;
import com.medibook.domain.payment.provider.PaymentProviderPort;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.PaymentEvent;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentRepository          paymentRepository;
    private final InvoiceRepository          invoiceRepository;
    private final AppointmentRepository      appointmentRepository;
    private final PaymentProviderFactory     providerFactory;
    private final OutboxEventRepository      outboxRepository;
    private final ObjectMapper               objectMapper;

    private final AtomicLong invoiceSeq = new AtomicLong(System.currentTimeMillis() % 1_000_000);

    @Transactional
    public PaymentResponse initiatePayment(InitiatePaymentRequest req, UserPrincipal principal) {
        String idempotencyKey = req.getIdempotencyKey() != null
                ? req.getIdempotencyKey()
                : UUID.randomUUID().toString();

        // Idempotency: return existing if key matches
        return paymentRepository.findByIdempotencyKey(idempotencyKey)
                .map(existing -> {
                    log.info("Idempotent payment request: key={}", idempotencyKey);
                    return toResponseWithUrl(existing, null);
                })
                .orElseGet(() -> createNewPayment(req, principal, idempotencyKey));
    }

    private PaymentResponse createNewPayment(InitiatePaymentRequest req, UserPrincipal principal, String idempotencyKey) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(req.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", req.getAppointmentId()));

        if (!appointment.getPatient().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized for this appointment", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (appointment.getStatus() == AppointmentStatus.CANCELLED) {
            throw new MediBookException("Cannot pay for a cancelled appointment", HttpStatus.BAD_REQUEST, "APPOINTMENT_CANCELLED");
        }

        if (paymentRepository.existsByAppointmentIdAndStatusIn(req.getAppointmentId(),
                List.of(PaymentStatus.SUCCESSFUL, PaymentStatus.PENDING))) {
            throw new MediBookException("Payment already exists for this appointment",
                    HttpStatus.CONFLICT, "PAYMENT_EXISTS");
        }

        User patient = appointment.getPatient();
        Doctor doctor = appointment.getDoctor();
        BigDecimal amount = req.getAmount() != null
                ? req.getAmount()
                : doctor.getEffectiveConsultationFee();

        PaymentProviderPort providerPort = providerFactory.get(req.getProvider());
        PaymentProviderPort.InitiateResult result = providerPort.initiatePayment(
                new PaymentProviderPort.InitiateRequest(
                        idempotencyKey,
                        patient.getEmail(),
                        patient.getFullName(),
                        amount,
                        req.getCurrency(),
                        "Consultation with Dr. " + doctor.getUser().getFullName(),
                        req.getCallbackUrl()
                )
        );

        Payment payment = Payment.builder()
                .appointment(appointment)
                .patient(patient)
                .idempotencyKey(idempotencyKey)
                .provider(req.getProvider())
                .providerRef(result.providerRef())
                .amount(amount)
                .currency(req.getCurrency())
                .status(PaymentStatus.PENDING)
                .build();

        Payment saved = paymentRepository.save(payment);

        // Create invoice
        createInvoice(saved, doctor, patient, amount);

        // Publish via outbox
        publishPaymentEvent(saved, "INITIATED");

        log.info("Payment [{}] initiated for appointment [{}] via [{}]",
                saved.getId(), appointment.getId(), req.getProvider());

        return toResponseWithUrl(saved, result.authorizationUrl());
    }

    @Transactional
    public PaymentResponse verifyPayment(Long paymentId, UserPrincipal principal) {
        Payment payment = paymentRepository.findByIdWithDetails(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (!payment.getPatient().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (payment.getStatus() == PaymentStatus.SUCCESSFUL) {
            return PaymentResponse.fromEntity(payment);
        }

        PaymentProviderPort providerPort = providerFactory.get(payment.getProvider());
        PaymentProviderPort.VerifyResult result = providerPort.verifyPayment(payment.getProviderRef());

        try {
            PaymentStatus newStatus = mapProviderStatus(result.status(), payment.getProvider());
            payment.setStatus(newStatus);

            if (newStatus == PaymentStatus.SUCCESSFUL) {
                markInvoicePaid(payment);
            }

            Payment updated = paymentRepository.save(payment);
            publishPaymentEvent(updated, newStatus == PaymentStatus.SUCCESSFUL ? "SUCCEEDED" : "FAILED");
            return PaymentResponse.fromEntity(updated);
        } catch (OptimisticLockingFailureException ex) {
            throw new MediBookException("Payment was modified concurrently. Please retry.", HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }
    }

    @Transactional
    public PaymentResponse refundPayment(Long paymentId, BigDecimal amount, String reason, UserPrincipal principal) {
        Payment payment = paymentRepository.findByIdWithDetails(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (payment.getStatus() != PaymentStatus.SUCCESSFUL) {
            throw new MediBookException("Only successful payments can be refunded",
                    HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_STATUS");
        }

        if (payment.getRefundedAt() != null) {
            throw new MediBookException("Payment already refunded", HttpStatus.CONFLICT, "ALREADY_REFUNDED");
        }

        BigDecimal refundAmount = amount != null ? amount : payment.getAmount();
        if (refundAmount.compareTo(payment.getAmount()) > 0) {
            throw new MediBookException("Refund amount exceeds payment amount",
                    HttpStatus.BAD_REQUEST, "REFUND_EXCEEDS_PAYMENT");
        }

        PaymentProviderPort providerPort = providerFactory.get(payment.getProvider());
        PaymentProviderPort.RefundResult result = providerPort.refundPayment(
                payment.getProviderRef(), refundAmount, reason);

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundAmount(refundAmount);
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundRef(result.refundRef());

        Payment updated = paymentRepository.save(payment);
        publishPaymentEvent(updated, "REFUNDED");

        log.info("Payment [{}] refunded: amount={} ref={}", paymentId, refundAmount, result.refundRef());
        return PaymentResponse.fromEntity(updated);
    }

    @Transactional(readOnly = true)
    public Page<PaymentResponse> getPaymentsForPatient(Long patientId, Pageable pageable) {
        return paymentRepository.findByPatientId(patientId, pageable).map(PaymentResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public InvoiceResponse getInvoice(Long invoiceId, UserPrincipal principal) {
        Invoice invoice = invoiceRepository.findByIdWithDetails(invoiceId)
                .orElseThrow(() -> new ResourceNotFoundException("Invoice", "id", invoiceId));

        if (!invoice.getPatient().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return InvoiceResponse.fromEntity(invoice);
    }

    @Transactional(readOnly = true)
    public Page<InvoiceResponse> getInvoicesForPatient(Long patientId, Pageable pageable) {
        return invoiceRepository.findByPatientId(patientId, pageable).map(InvoiceResponse::fromEntity);
    }

    private void createInvoice(Payment payment, Doctor doctor, User patient, BigDecimal amount) {
        String invoiceNumber = "INV-" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
                + "-" + String.format("%06d", invoiceSeq.incrementAndGet());

        InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .description("Consultation fee – Dr. " + doctor.getUser().getFullName())
                .quantity(1)
                .unitPrice(amount)
                .subtotal(amount)
                .build();

        Invoice invoice = Invoice.builder()
                .payment(payment)
                .invoiceNumber(invoiceNumber)
                .patient(patient)
                .doctor(doctor)
                .subtotal(amount)
                .total(amount)
                .currency(payment.getCurrency())
                .status("UNPAID")
                .issuedAt(LocalDateTime.now())
                .build();

        lineItem.setInvoice(invoice);
        invoice.getLineItems().add(lineItem);
        invoiceRepository.save(invoice);
    }

    private void markInvoicePaid(Payment payment) {
        invoiceRepository.findByPaymentId(payment.getId()).ifPresent(inv -> {
            inv.setStatus("PAID");
            inv.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(inv);
        });
    }

    private void publishPaymentEvent(Payment payment, String eventType) {
        PaymentEvent event = PaymentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(eventType)
                .schemaVersion(1)
                .paymentId(payment.getId())
                .appointmentId(payment.getAppointment().getId())
                .patientId(payment.getPatient().getId())
                .patientEmail(payment.getPatient().getEmail())
                .provider(payment.getProvider())
                .providerRef(payment.getProviderRef())
                .amount(payment.getAmount())
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .occurredAt(LocalDateTime.now())
                .build();

        try {
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("Payment")
                    .aggregateId(String.valueOf(payment.getId()))
                    .eventType(eventType)
                    .payload(objectMapper.writeValueAsString(event))
                    .topic(KafkaTopics.PAYMENT_EVENTS)
                    .build());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize payment event for outbox", e);
        }
    }

    private PaymentStatus mapProviderStatus(String status, com.medibook.domain.payment.entity.PaymentProvider provider) {
        return switch (provider) {
            case PAYSTACK -> switch (status.toLowerCase()) {
                case "success" -> PaymentStatus.SUCCESSFUL;
                case "failed" -> PaymentStatus.FAILED;
                default -> PaymentStatus.PENDING;
            };
            case FLUTTERWAVE -> switch (status.toLowerCase()) {
                case "successful" -> PaymentStatus.SUCCESSFUL;
                case "failed" -> PaymentStatus.FAILED;
                default -> PaymentStatus.PENDING;
            };
            case STRIPE -> switch (status.toLowerCase()) {
                case "succeeded" -> PaymentStatus.SUCCESSFUL;
                case "failed" -> PaymentStatus.FAILED;
                default -> PaymentStatus.PENDING;
            };
        };
    }

    private PaymentResponse toResponseWithUrl(Payment payment, String authorizationUrl) {
        PaymentResponse response = PaymentResponse.fromEntity(payment);
        response.setAuthorizationUrl(authorizationUrl);
        return response;
    }
}
