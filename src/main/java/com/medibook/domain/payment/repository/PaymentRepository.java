package com.medibook.domain.payment.repository;

import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentStatus;
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
public interface PaymentRepository extends JpaRepository<Payment, Long> {

    Optional<Payment> findByIdempotencyKey(String idempotencyKey);

    Optional<Payment> findByAppointmentId(Long appointmentId);

    Optional<Payment> findByProviderRef(String providerRef);

    Page<Payment> findByPatientId(Long patientId, Pageable pageable);

    boolean existsByAppointmentIdAndStatusIn(Long appointmentId, java.util.List<PaymentStatus> statuses);

    Optional<Payment> findFirstByAppointmentIdAndStatusInOrderByCreatedAtDesc(
            Long appointmentId, java.util.List<PaymentStatus> statuses);

    @Query("SELECT p FROM Payment p JOIN FETCH p.appointment JOIN FETCH p.patient WHERE p.id = :id")
    Optional<Payment> findByIdWithDetails(Long id);

    @Query("""
        SELECT p.status, COUNT(p), SUM(p.amount), SUM(COALESCE(p.refundAmount, 0))
        FROM Payment p
        WHERE p.createdAt BETWEEN :from AND :to
        GROUP BY p.status
        """)
    List<Object[]> getPaymentStats(java.time.LocalDateTime from, java.time.LocalDateTime to);

    // Soft delete methods (admin only)

    /**
     * Find payment by ID including soft-deleted records (admin only).
     */
    @Query(value = "SELECT * FROM payments WHERE id = :id", nativeQuery = true)
    Optional<Payment> findByIdIncludeDeleted(@Param("id") Long id);

    /**
     * Find deleted payments in a date range for audit/recovery.
     */
    @Query(value = "SELECT * FROM payments WHERE deleted_at IS NOT NULL AND deleted_at BETWEEN :from AND :to ORDER BY deleted_at DESC", nativeQuery = true)
    List<Payment> findDeletedBetween(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    /**
     * Count soft-deleted payments.
     */
    @Query(value = "SELECT COUNT(*) FROM payments WHERE deleted_at IS NOT NULL", nativeQuery = true)
    long countDeleted();
}
