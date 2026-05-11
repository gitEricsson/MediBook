package com.medibook.domain.payment.repository;

import com.medibook.domain.payment.entity.Invoice;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

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
}
