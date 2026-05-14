package com.medibook.domain.admin.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.AppointmentResponse;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.dto.ConsultationNoteResponse;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.payment.dto.InvoiceResponse;
import com.medibook.domain.payment.dto.PaymentResponse;
import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * Admin API for managing soft-deleted medical records (HIPAA/GDPR compliance).
 * Allows viewing deleted records, restoring them, and auditing deletions.
 */
@RestController
@RequestMapping("/api/v1/admin/soft-delete")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin — Soft Delete Management", description = "Admin-only soft delete recovery and audit operations")
@SecurityRequirement(name = "bearerAuth")
@Slf4j
public class AdminSoftDeleteController {

    private final AppointmentRepository appointmentRepository;
    private final ConsultationNoteRepository consultationNoteRepository;
    private final PaymentRepository paymentRepository;
    private final InvoiceRepository invoiceRepository;

    /**
     * Get deletion statistics for all soft-deleted medical records.
     */
    @GetMapping("/stats")
    @Operation(summary = "Get statistics on soft-deleted records")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getDeletionStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("deletedAppointments", appointmentRepository.countDeleted());
        stats.put("deletedConsultationNotes", consultationNoteRepository.countDeleted());
        stats.put("deletedPayments", paymentRepository.countDeleted());
        stats.put("deletedInvoices", invoiceRepository.countDeleted());
        return ResponseEntity.ok(ApiResponse.ok("Deletion statistics", stats));
    }

    /**
     * Restore a soft-deleted appointment.
     */
    @PostMapping("/appointments/{id}/restore")
    @Operation(summary = "Restore a soft-deleted appointment")
    public ResponseEntity<ApiResponse<AppointmentResponse>> restoreAppointment(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        Appointment appointment = appointmentRepository.findByIdIncludeDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Appointment not found with id: " + id));

        if (appointment.getDeletedAt() == null) {
            throw new IllegalStateException("Appointment is not deleted");
        }

        appointment.restore();
        appointmentRepository.save(appointment);

        log.info("Appointment [{}] restored by admin [{}]", id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Appointment restored", AppointmentResponse.fromEntity(appointment)));
    }

    /**
     * Restore a soft-deleted consultation note.
     */
    @PostMapping("/consultation-notes/{id}/restore")
    @Operation(summary = "Restore a soft-deleted consultation note")
    public ResponseEntity<ApiResponse<ConsultationNoteResponse>> restoreConsultationNote(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        ConsultationNote note = consultationNoteRepository.findByIdIncludeDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Consultation note not found with id: " + id));

        if (note.getDeletedAt() == null) {
            throw new IllegalStateException("Consultation note is not deleted");
        }

        note.restore();
        consultationNoteRepository.save(note);

        log.info("Consultation note [{}] restored by admin [{}]", id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Consultation note restored", ConsultationNoteResponse.fromEntity(note)));
    }

    /**
     * Restore a soft-deleted payment.
     */
    @PostMapping("/payments/{id}/restore")
    @Operation(summary = "Restore a soft-deleted payment")
    public ResponseEntity<ApiResponse<PaymentResponse>> restorePayment(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        Payment payment = paymentRepository.findByIdIncludeDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Payment not found with id: " + id));

        if (payment.getDeletedAt() == null) {
            throw new IllegalStateException("Payment is not deleted");
        }

        payment.restore();
        paymentRepository.save(payment);

        log.info("Payment [{}] restored by admin [{}]", id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Payment restored", PaymentResponse.fromEntity(payment)));
    }

    /**
     * Restore a soft-deleted invoice.
     */
    @PostMapping("/invoices/{id}/restore")
    @Operation(summary = "Restore a soft-deleted invoice")
    public ResponseEntity<ApiResponse<InvoiceResponse>> restoreInvoice(
            @PathVariable Long id,
            @AuthenticationPrincipal UserPrincipal principal) {

        Invoice invoice = invoiceRepository.findByIdIncludeDeleted(id)
                .orElseThrow(() -> new EntityNotFoundException("Invoice not found with id: " + id));

        if (invoice.getDeletedAt() == null) {
            throw new IllegalStateException("Invoice is not deleted");
        }

        invoice.restore();
        invoiceRepository.save(invoice);

        log.info("Invoice [{}] restored by admin [{}]", id, principal.getId());
        return ResponseEntity.ok(ApiResponse.ok("Invoice restored", InvoiceResponse.fromEntity(invoice)));
    }

    /**
     * List deleted appointments in a date range.
     */
    @GetMapping("/appointments/deleted")
    @Operation(summary = "List deleted appointments in date range")
    public ResponseEntity<ApiResponse<java.util.List<AppointmentResponse>>> listDeletedAppointments(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {

        var deleted = appointmentRepository.findDeletedBetween(from, to)
                .stream()
                .map(AppointmentResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("Deleted appointments", deleted));
    }

    /**
     * List deleted consultation notes in a date range.
     */
    @GetMapping("/consultation-notes/deleted")
    @Operation(summary = "List deleted consultation notes in date range")
    public ResponseEntity<ApiResponse<java.util.List<ConsultationNoteResponse>>> listDeletedConsultationNotes(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {

        var deleted = consultationNoteRepository.findDeletedBetween(from, to)
                .stream()
                .map(ConsultationNoteResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("Deleted consultation notes", deleted));
    }

    /**
     * List deleted payments in a date range.
     */
    @GetMapping("/payments/deleted")
    @Operation(summary = "List deleted payments in date range")
    public ResponseEntity<ApiResponse<java.util.List<PaymentResponse>>> listDeletedPayments(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {

        var deleted = paymentRepository.findDeletedBetween(from, to)
                .stream()
                .map(PaymentResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("Deleted payments", deleted));
    }

    /**
     * List deleted invoices in a date range.
     */
    @GetMapping("/invoices/deleted")
    @Operation(summary = "List deleted invoices in date range")
    public ResponseEntity<ApiResponse<java.util.List<InvoiceResponse>>> listDeletedInvoices(
            @RequestParam LocalDateTime from,
            @RequestParam LocalDateTime to) {

        var deleted = invoiceRepository.findDeletedBetween(from, to)
                .stream()
                .map(InvoiceResponse::fromEntity)
                .toList();

        return ResponseEntity.ok(ApiResponse.ok("Deleted invoices", deleted));
    }
}
