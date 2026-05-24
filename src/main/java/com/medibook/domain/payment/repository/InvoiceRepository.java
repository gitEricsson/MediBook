package com.medibook.domain.payment.repository;

import com.medibook.domain.payment.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    Optional<Invoice> findByInvoiceNumber(String invoiceNumber);

    Optional<Invoice> findByPaymentId(Long paymentId);

    /**
     * Returns the MOST RECENT invoice for an appointment, or empty if none exists.
     *
     * An appointment legitimately has multiple invoices when the patient retries
     * payment (gateway failure, gave up, switched provider). Before this fix the
     * query was `Optional<Invoice>` with no ordering, which Spring Data interprets
     * as "must be unique-or-empty" and throws IncorrectResultSizeDataAccessException
     * on the second invoice — taking the whole /me/appointments?tab=upcoming
     * endpoint to 500.
     *
     * ORDER BY ... LIMIT 1 (set via Pageable) returns the latest, which is the
     * one whose status represents the current settlement state. The caller still
     * filters out PAID to compute "outstanding balance".
     */
    @Query("""
        SELECT i FROM Invoice i
        JOIN FETCH i.payment p
        WHERE p.appointment.id = :appointmentId
        ORDER BY i.createdAt DESC
        """)
    List<Invoice> findByAppointmentIdOrderByCreatedAtDesc(
            @Param("appointmentId") Long appointmentId,
            org.springframework.data.domain.Pageable pageable);

    /** Convenience wrapper: most recent invoice for an appointment. */
    default Optional<Invoice> findByAppointmentId(Long appointmentId) {
        var page = org.springframework.data.domain.PageRequest.of(0, 1);
        var rows = findByAppointmentIdOrderByCreatedAtDesc(appointmentId, page);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    Page<Invoice> findByPatientId(Long patientId, Pageable pageable);

    @Query("""
        SELECT i FROM Invoice i
        JOIN FETCH i.payment
        JOIN FETCH i.patient
        JOIN FETCH i.doctor d
        JOIN FETCH d.user
        WHERE i.id = :id
        """)
    Optional<Invoice> findByIdWithDetails(Long id);

    // Soft delete methods (admin only)

    /**
     * Find invoice by ID including soft-deleted records (admin only).
     */
    @Query(value = "SELECT * FROM invoices WHERE id = :id", nativeQuery = true)
    Optional<Invoice> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted invoices in a date range for audit/recovery.
     */
    @Query(value = "SELECT * FROM invoices WHERE deleted_at IS NOT NULL AND deleted_at BETWEEN :from AND :to ORDER BY deleted_at DESC", nativeQuery = true)
    List<Invoice> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted invoices.
     */
    @Query(value = "SELECT COUNT(*) FROM invoices WHERE deleted_at IS NOT NULL", nativeQuery = true)
    long countDeleted();
}
