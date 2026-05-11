package com.medibook.domain.payment.repository;

import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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

    @Query("SELECT p FROM Payment p JOIN FETCH p.appointment JOIN FETCH p.patient WHERE p.id = :id")
    Optional<Payment> findByIdWithDetails(Long id);

    @Query("""
        SELECT p.status, COUNT(p), SUM(p.amount), SUM(COALESCE(p.refundAmount, 0))
        FROM Payment p
        WHERE p.createdAt BETWEEN :from AND :to
        GROUP BY p.status
        """)
    List<Object[]> getPaymentStats(java.time.LocalDateTime from, java.time.LocalDateTime to);
}
