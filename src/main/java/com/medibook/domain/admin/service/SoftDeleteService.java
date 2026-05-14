package com.medibook.domain.admin.service;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for managing soft-deleted records.
 * Provides methods for soft delete/restore operations with proper auditing.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class SoftDeleteService {

    private final AppointmentRepository appointmentRepository;
    private final ConsultationNoteRepository consultationNoteRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * Get statistics on all soft-deleted medical records.
     */
    public Map<String, Object> getDeletionStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("deletedAppointments", appointmentRepository.countDeleted());
        stats.put("deletedConsultationNotes", consultationNoteRepository.countDeleted());
        stats.put("deletedPayments", paymentRepository.countDeleted());
        stats.put("deletedInvoices", invoiceRepository.countDeleted());
        stats.put("timestamp", LocalDateTime.now());
        return stats;
    }

    /**
     * Soft delete an appointment by ID.
     */
    public Appointment softDeleteAppointment(Long appointmentId, Long deletedBy) {
        Appointment appointment = appointmentRepository.findByIdIncludeDeleted(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found: " + appointmentId));

        if (appointment.isDeleted()) {
            throw new IllegalStateException("Appointment is already deleted");
        }

        appointment.softDelete(deletedBy);
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Appointment [{}] soft-deleted by user [{}]", appointmentId, deletedBy);
        return saved;
    }

    /**
     * Soft delete a consultation note by ID.
     */
    public ConsultationNote softDeleteConsultationNote(Long noteId, Long deletedBy) {
        ConsultationNote note = consultationNoteRepository.findByIdIncludeDeleted(noteId)
                .orElseThrow(() -> new IllegalArgumentException("Consultation note not found: " + noteId));

        if (note.isDeleted()) {
            throw new IllegalStateException("Consultation note is already deleted");
        }

        note.softDelete(deletedBy);
        ConsultationNote saved = consultationNoteRepository.save(note);
        log.info("Consultation note [{}] soft-deleted by user [{}]", noteId, deletedBy);
        return saved;
    }

    /**
     * Soft delete a payment by ID.
     */
    public Payment softDeletePayment(Long paymentId, Long deletedBy) {
        Payment payment = paymentRepository.findByIdIncludeDeleted(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (payment.isDeleted()) {
            throw new IllegalStateException("Payment is already deleted");
        }

        payment.softDelete(deletedBy);
        Payment saved = paymentRepository.save(payment);
        log.info("Payment [{}] soft-deleted by user [{}]", paymentId, deletedBy);
        return saved;
    }

    /**
     * Soft delete an invoice by ID.
     */
    public Invoice softDeleteInvoice(Long invoiceId, Long deletedBy) {
        Invoice invoice = invoiceRepository.findByIdIncludeDeleted(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        if (invoice.isDeleted()) {
            throw new IllegalStateException("Invoice is already deleted");
        }

        invoice.softDelete(deletedBy);
        Invoice saved = invoiceRepository.save(invoice);
        log.info("Invoice [{}] soft-deleted by user [{}]", invoiceId, deletedBy);
        return saved;
    }

    /**
     * Restore a soft-deleted appointment.
     */
    public Appointment restoreAppointment(Long appointmentId) {
        Appointment appointment = appointmentRepository.findByIdIncludeDeleted(appointmentId)
                .orElseThrow(() -> new IllegalArgumentException("Appointment not found: " + appointmentId));

        if (!appointment.isDeleted()) {
            throw new IllegalStateException("Appointment is not deleted");
        }

        appointment.restore();
        Appointment saved = appointmentRepository.save(appointment);
        log.info("Appointment [{}] restored", appointmentId);
        return saved;
    }

    /**
     * Restore a soft-deleted consultation note.
     */
    public ConsultationNote restoreConsultationNote(Long noteId) {
        ConsultationNote note = consultationNoteRepository.findByIdIncludeDeleted(noteId)
                .orElseThrow(() -> new IllegalArgumentException("Consultation note not found: " + noteId));

        if (!note.isDeleted()) {
            throw new IllegalStateException("Consultation note is not deleted");
        }

        note.restore();
        ConsultationNote saved = consultationNoteRepository.save(note);
        log.info("Consultation note [{}] restored", noteId);
        return saved;
    }

    /**
     * Restore a soft-deleted payment.
     */
    public Payment restorePayment(Long paymentId) {
        Payment payment = paymentRepository.findByIdIncludeDeleted(paymentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment not found: " + paymentId));

        if (!payment.isDeleted()) {
            throw new IllegalStateException("Payment is not deleted");
        }

        payment.restore();
        Payment saved = paymentRepository.save(payment);
        log.info("Payment [{}] restored", paymentId);
        return saved;
    }

    /**
     * Restore a soft-deleted invoice.
     */
    public Invoice restoreInvoice(Long invoiceId) {
        Invoice invoice = invoiceRepository.findByIdIncludeDeleted(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found: " + invoiceId));

        if (!invoice.isDeleted()) {
            throw new IllegalStateException("Invoice is not deleted");
        }

        invoice.restore();
        Invoice saved = invoiceRepository.save(invoice);
        log.info("Invoice [{}] restored", invoiceId);
        return saved;
    }

    /**
     * Get count of all soft-deleted records for compliance reporting.
     */
    public long getTotalDeletedCount() {
        return appointmentRepository.countDeleted()
                + consultationNoteRepository.countDeleted()
                + paymentRepository.countDeleted()
                + invoiceRepository.countDeleted();
    }
}
