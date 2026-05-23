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

import com.medibook.common.sequence.SequenceService;
import com.medibook.config.HospitalProperties;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import io.micrometer.core.instrument.MeterRegistry;

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
    private final SequenceService            sequenceService;
    private final HospitalProperties         hospitalProperties;
    private final PricingEngine              pricingEngine;
    private final MeterRegistry              meterRegistry;

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
                List.of(PaymentStatus.SUCCESSFUL))) {
            throw new MediBookException("Appointment is already paid",
                    HttpStatus.CONFLICT, "ALREADY_PAID");
        }

        // If a PENDING payment exists, recycle it. The user retried (e.g. picked a different
        // gateway, or the first init failed mid-redirect). Cancel the stale pending row and
        // from the caller's perspective without rejecting legitimate retries.
        paymentRepository.findFirstByAppointmentIdAndStatusInOrderByCreatedAtDesc(
                req.getAppointmentId(), List.of(PaymentStatus.PENDING, PaymentStatus.INITIATED))
                .ifPresent(stale -> {
                    log.info("Recycling stale payment [{}] (status={}) for appointment [{}]",
                            stale.getId(), stale.getStatus(), req.getAppointmentId());
                    stale.setStatus(PaymentStatus.CANCELLED);
                    paymentRepository.save(stale);
                });

        User patient = appointment.getPatient();
        Doctor doctor = appointment.getDoctor();
        // Server-side pricing wins: PricingEngine applies department base + consultation-type
        // modifier + senior surcharge + medium surcharge. Client-supplied amount is honored
        // only when it is >= the canonical fee (e.g. optional add-ons); otherwise ignored.
        AppointmentType consultationType = appointment.getConsultationType() != null
                ? appointment.getConsultationType()
                : AppointmentType.FIRST_VISIT;
        ConsultationMedium medium = appointment.getConsultationMedium() != null
                ? appointment.getConsultationMedium()
                : ConsultationMedium.PHYSICAL;
        BigDecimal canonicalFee = pricingEngine.calculate(doctor, consultationType, medium);
        BigDecimal amount = (req.getAmount() != null && req.getAmount().compareTo(canonicalFee) >= 0)
                ? req.getAmount()
                : canonicalFee;

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

        // Fail loudly when the gateway returned no checkout URL. Otherwise we'd persist
        // an orphan PENDING row that blocks every subsequent retry with a 409.
        if (result.authorizationUrl() == null || result.authorizationUrl().isBlank()) {
            log.warn("Provider [{}] returned no authorization URL for appointment [{}] — likely circuit-open or upstream rejection. providerRef={}",
                    req.getProvider(), appointment.getId(), result.providerRef());
            throw new MediBookException(
                    "The payment gateway did not return a checkout link. Please try again in a moment.",
                    HttpStatus.BAD_GATEWAY,
                    "GATEWAY_UNAVAILABLE");
        }

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

        meterRegistry.counter("payments.initiated",
                "provider", req.getProvider() != null ? req.getProvider().name() : "UNKNOWN"
        ).increment();

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
            if (newStatus == PaymentStatus.SUCCESSFUL) {
                meterRegistry.counter("payments.succeeded",
                        "provider", updated.getProvider() != null ? updated.getProvider().name() : "UNKNOWN"
                ).increment();
            }
            return PaymentResponse.fromEntity(updated);
        } catch (OptimisticLockingFailureException ex) {
            throw new MediBookException("Payment was modified concurrently. Please retry.", HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION");
        }
    }

    /**
     * Initiate a refund saga for a successful payment.
     * Idempotent — if the payment is already REFUNDED the call returns immediately.
     * The actual gateway call is made synchronously here; downstream effects
     * (appointment → REFUNDED status, notification emails) are handled by the
     * REFUND_INITIATED Kafka consumer so they don't block this thread.
     */
    @Transactional
    public PaymentResponse refundPayment(Long paymentId, BigDecimal amount, String reason, UserPrincipal principal) {
        Payment payment = paymentRepository.findByIdWithDetails(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (!payment.getPatient().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("Idempotent refund: payment [{}] already refunded", paymentId);
            return PaymentResponse.fromEntity(payment);
        }

        if (payment.getStatus() != PaymentStatus.SUCCESSFUL) {
            throw new MediBookException("Only successful payments can be refunded",
                    HttpStatus.BAD_REQUEST, "INVALID_PAYMENT_STATUS");
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

        // Publish REFUND_INITIATED to the outbox — consumer handles appointment
        // status → REFUNDED and sends refund notification email.
        publishRefundInitiatedEvent(updated, refundAmount, reason);

        meterRegistry.counter("payments.refunded",
                "provider", updated.getProvider() != null ? updated.getProvider().name() : "UNKNOWN",
                "trigger", "manual"
        ).increment();
        log.info("Payment [{}] refunded: amount={} ref={}", paymentId, refundAmount, result.refundRef());
        return PaymentResponse.fromEntity(updated);
    }

    private void publishRefundInitiatedEvent(Payment payment, BigDecimal refundAmount, String reason) {
        PaymentEvent event = PaymentEvent.builder()
                .eventId("refund-" + payment.getId() + "-" + System.currentTimeMillis())
                .eventType("REFUND_INITIATED")
                .schemaVersion(1)
                .paymentId(payment.getId())
                .appointmentId(payment.getAppointment().getId())
                .patientId(payment.getPatient().getId())
                .patientEmail(payment.getPatient().getEmail())
                .provider(payment.getProvider())
                .providerRef(payment.getRefundRef())
                .amount(refundAmount)
                .currency(payment.getCurrency())
                .status(payment.getStatus())
                .occurredAt(LocalDateTime.now())
                .build();

        try {
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("Payment")
                    .aggregateId(String.valueOf(payment.getId()))
                    .eventType("REFUND_INITIATED")
                    .topic(KafkaTopics.REFUND_EVENTS)
                    .payload(objectMapper.writeValueAsString(event))
                    .build());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize REFUND_INITIATED event for payment [{}]", payment.getId(), e);
        }
    }

    /**
     * System-initiated refund — no principal auth check.
     * Idempotent: if the payment is already REFUNDED the call returns silently.
     * Called by RefundEventConsumer after consuming CANCELLATION_REFUND_REQUESTED.
     */
    @Transactional
    public void executeSystemRefund(Long paymentId, String reason) {
        Payment payment = paymentRepository.findByIdWithDetails(paymentId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment", "id", paymentId));

        if (payment.getStatus() == PaymentStatus.REFUNDED) {
            log.info("[SystemRefund] Payment [{}] already refunded — skipping", paymentId);
            return;
        }
        if (payment.getStatus() != PaymentStatus.SUCCESSFUL) {
            log.warn("[SystemRefund] Payment [{}] has status {} — cannot refund", paymentId, payment.getStatus());
            return;
        }

        BigDecimal refundAmount = payment.getAmount();
        PaymentProviderPort providerPort = providerFactory.get(payment.getProvider());
        PaymentProviderPort.RefundResult result = providerPort.refundPayment(
                payment.getProviderRef(), refundAmount, reason);

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setRefundAmount(refundAmount);
        payment.setRefundedAt(LocalDateTime.now());
        payment.setRefundRef(result.refundRef());
        paymentRepository.save(payment);

        meterRegistry.counter("payments.refunded",
                "provider", payment.getProvider() != null ? payment.getProvider().name() : "UNKNOWN",
                "trigger", "system"
        ).increment();
        log.info("[SystemRefund] Payment [{}] refunded: amount={} ref={}", paymentId, refundAmount, result.refundRef());
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
        long nextSeq = sequenceService.getNextValue("invoice");
        String invoiceNumber = "INV-" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
                + "-" + String.format("%06d", nextSeq);

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

    /**
     * Idempotent post-payment side effects:
     *   1. Flip the linked invoice to PAID (no-op if already PAID).
     *   2. Flip the linked appointment PENDING → CONFIRMED (no-op for any other status).
     *
     * Called from both {@link #verifyPayment} (client returns from gateway) and
     * {@link #handleSuccessfulWebhookPayment} (gateway webhook). Whichever arrives
     * first wins; the second is a guarded no-op so racing those two paths is safe.
     * We never auto-confirm an appointment that's been cancelled, completed, or
     * no-show'd — those statuses are terminal from a billing perspective.
     */
    private void markInvoicePaid(Payment payment) {
        invoiceRepository.findByPaymentId(payment.getId()).ifPresent(inv -> {
            if ("PAID".equalsIgnoreCase(inv.getStatus())) return;
            inv.setStatus("PAID");
            inv.setPaidAt(LocalDateTime.now());
            invoiceRepository.save(inv);
        });

        Appointment appointment = payment.getAppointment();
        if (appointment == null) return;
        if (appointment.getStatus() != AppointmentStatus.PENDING) {
            log.debug("Skipping appointment auto-confirm — appointment [{}] is in status [{}]",
                    appointment.getId(), appointment.getStatus());
            return;
        }
        appointment.setStatus(AppointmentStatus.CONFIRMED);
        appointmentRepository.save(appointment);
        log.info("Appointment [{}] auto-confirmed after successful payment [{}]",
                appointment.getId(), payment.getId());
    }

    private void publishPaymentEvent(Payment payment, String eventType) {
        // Deterministic eventId per (paymentId, eventType) so a retried verify or a
        // duplicate webhook delivery doesn't double-fire downstream emails/STOMP.
        // Consumer dedup (ProcessedEventRepository.existsById) will skip the second
        // occurrence. UUID-based generation would have given each retry a fresh id
        // and bypassed that dedup.
        String deterministicEventId = "payment-" + payment.getId() + "-" + eventType.toLowerCase();
        PaymentEvent event = PaymentEvent.builder()
                .eventId(deterministicEventId)
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
            throw new RuntimeException("Failed to serialize payment event", e);
        }
    }

    /**
     * Called by WebhookProcessingService when a webhook confirms a payment as successful.
     * Marks the invoice paid and publishes the payment event via the outbox pattern.
     */
    @Transactional
    public void handleSuccessfulWebhookPayment(Payment payment) {
        markInvoicePaid(payment);
        publishPaymentEvent(payment, "SUCCEEDED");
    }

    private PaymentStatus mapProviderStatus(String status, com.medibook.domain.payment.entity.PaymentProvider provider) {
        return switch (provider) {
            case PAYSTACK -> switch (status.toLowerCase()) {
                case "success"               -> PaymentStatus.SUCCESSFUL;
                case "failed"                -> PaymentStatus.FAILED;
                default                      -> PaymentStatus.PENDING;
            };
            case FLUTTERWAVE -> switch (status.toLowerCase()) {
                case "successful"            -> PaymentStatus.SUCCESSFUL;
                case "failed"                -> PaymentStatus.FAILED;
                default                      -> PaymentStatus.PENDING;
            };
            case STRIPE -> switch (status.toLowerCase()) {
                case "succeeded"             -> PaymentStatus.SUCCESSFUL;
                case "failed"                -> PaymentStatus.FAILED;
                default                      -> PaymentStatus.PENDING;
            };
            case MONNIFY -> switch (status.toUpperCase()) {
                case "PAID", "OVERPAID"      -> PaymentStatus.SUCCESSFUL;
                case "FAILED", "EXPIRED"     -> PaymentStatus.FAILED;
                case "CANCELLED"             -> PaymentStatus.CANCELLED;
                default                      -> PaymentStatus.PENDING;
            };
        };
    }

    private PaymentResponse toResponseWithUrl(Payment payment, String authorizationUrl) {
        PaymentResponse response = PaymentResponse.fromEntity(payment);
        response.setAuthorizationUrl(authorizationUrl);
        return response;
    }
}
