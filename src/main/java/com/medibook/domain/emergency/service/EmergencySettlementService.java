package com.medibook.domain.emergency.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.common.sequence.SequenceService;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.InvoiceLineItem;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.payment.service.PricingEngine;
import com.medibook.domain.user.entity.User;
import com.medibook.infrastructure.metrics.EmergencyMetrics;
import com.medibook.messaging.KafkaTopics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.outbox.OutboxEvent;
import com.medibook.messaging.outbox.OutboxEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * Handles post-consultation billing for EMERGENCY appointments.
 *
 * When a doctor completes an emergency consultation the appointment transitions
 * EMERGENCY_PENDING_SETTLEMENT → COMPLETED via AppointmentTransitionService.
 * This service generates the outstanding invoice and publishes
 * OUTSTANDING_BALANCE_CREATED so the notification service can prompt the patient.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencySettlementService {

    private final AppointmentRepository appointmentRepository;
    private final PaymentRepository     paymentRepository;
    private final InvoiceRepository     invoiceRepository;
    private final PricingEngine         pricingEngine;
    private final SequenceService       sequenceService;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper          objectMapper;
    private final EmergencyMetrics      emergencyMetrics;

    /**
     * Generate a post-consultation invoice for a completed emergency appointment.
     * Idempotent — if an invoice already exists for this appointment, returns it unchanged.
     */
    @Transactional
    public Invoice generateOutstandingInvoice(Long appointmentId) {
        return invoiceRepository.findByAppointmentId(appointmentId)
                .orElseGet(() -> createInvoice(appointmentId));
    }

    private Invoice createInvoice(Long appointmentId) {
        Appointment appt = appointmentRepository.findByIdWithDetails(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));

        if (appt.getStatus() != AppointmentStatus.COMPLETED
                && appt.getStatus() != AppointmentStatus.EMERGENCY_PENDING_SETTLEMENT) {
            throw new MediBookException(
                    "Emergency invoice can only be generated for COMPLETED or EMERGENCY_PENDING_SETTLEMENT appointments.",
                    HttpStatus.BAD_REQUEST, "INVALID_APPOINTMENT_STATUS");
        }

        User patient = appt.getPatient();
        Doctor doctor = appt.getDoctor();

        AppointmentType consultationType = appt.getConsultationType() != null
                ? appt.getConsultationType()
                : AppointmentType.EMERGENCY;
        ConsultationMedium medium = appt.getConsultationMedium() != null
                ? appt.getConsultationMedium()
                : ConsultationMedium.VIDEO;

        BigDecimal fee = pricingEngine.calculate(doctor, consultationType, medium);

        // Create a placeholder PENDING payment representing the outstanding balance.
        Payment payment = Payment.builder()
                .appointment(appt)
                .patient(patient)
                .idempotencyKey("emg-" + appointmentId + "-" + UUID.randomUUID())
                .provider(com.medibook.domain.payment.entity.PaymentProvider.PAYSTACK)
                .amount(fee)
                .currency("NGN")
                .status(PaymentStatus.PENDING)
                .build();
        Payment savedPayment = paymentRepository.save(payment);

        long seq = sequenceService.getNextValue("invoice");
        String invoiceNumber = "EMG-" + DateTimeFormatter.ofPattern("yyyyMMdd").format(LocalDateTime.now())
                + "-" + String.format("%05d", seq);

        InvoiceLineItem lineItem = InvoiceLineItem.builder()
                .description("Emergency Consultation – Dr. " + doctor.getUser().getFullName())
                .quantity(1)
                .unitPrice(fee)
                .subtotal(fee)
                .build();

        Invoice invoice = Invoice.builder()
                .payment(savedPayment)
                .invoiceNumber(invoiceNumber)
                .patient(patient)
                .doctor(doctor)
                .subtotal(fee)
                .discount(BigDecimal.ZERO)
                .total(fee)
                .currency("NGN")
                .status("UNPAID")
                .issuedAt(LocalDateTime.now())
                .dueDate(LocalDateTime.now().plusDays(7).toLocalDate())
                .notes("Payment due within 7 days of emergency consultation.")
                .lineItems(List.of(lineItem))
                .build();
        lineItem.setInvoice(invoice);

        Invoice saved = invoiceRepository.save(invoice);
        publishOutstandingBalanceEvent(appt, saved, fee);
        emergencyMetrics.recordInvoiceAmount(fee.doubleValue());

        log.info("Emergency invoice [{}] generated for appointment [{}], amount={} NGN",
                invoiceNumber, appointmentId, fee);
        return saved;
    }

    private void publishOutstandingBalanceEvent(Appointment appt, Invoice invoice, BigDecimal amount) {
        AppointmentEvent event = AppointmentEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType("OUTSTANDING_BALANCE_CREATED")
                .appointmentId(appt.getId())
                .patientId(appt.getPatient().getId())
                .patientEmail(appt.getPatient().getEmail())
                .patientName(appt.getPatient().getFullName())
                .doctorId(appt.getDoctor().getUser().getId())
                .doctorEmail(appt.getDoctor().getUser().getEmail())
                .doctorName(appt.getDoctor().getUser().getFullName())
                .departmentName(appt.getDoctor().getDepartment().getName())
                .scheduledAt(appt.getScheduledAt())
                .status(appt.getStatus())
                .occurredAt(LocalDateTime.now())
                .build();

        try {
            outboxRepository.save(OutboxEvent.builder()
                    .aggregateType("Invoice")
                    .aggregateId(String.valueOf(invoice.getId()))
                    .eventType("OUTSTANDING_BALANCE_CREATED")
                    .topic(KafkaTopics.OUTSTANDING_BALANCE_EVENTS)
                    .payload(objectMapper.writeValueAsString(event))
                    .build());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize OUTSTANDING_BALANCE_CREATED event for appointment [{}]",
                    appt.getId(), e);
        }
    }
}
