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
    @Query("""
        SELECT i FROM Invoice i
        WHERE i.id = :id
        """)
    Optional<Invoice> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted invoices in a date range for audit/recovery.
     */
    @Query("""
        SELECT i FROM Invoice i
        WHERE i.deletedAt IS NOT NULL
        AND i.deletedAt BETWEEN :from AND :to
        ORDER BY i.deletedAt DESC
        """)
    List<Invoice> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted invoices.
     */
    @Query("""
        SELECT COUNT(i) FROM Invoice i
        WHERE i.deletedAt IS NOT NULL
        """)
    long countDeleted();
}
