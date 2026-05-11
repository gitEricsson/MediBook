package com.medibook.domain.waitlist.repository;

import com.medibook.domain.waitlist.entity.WaitlistEntry;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface WaitlistRepository extends JpaRepository<WaitlistEntry, Long> {

    Page<WaitlistEntry> findByPatientId(Long patientId, Pageable pageable);

    Page<WaitlistEntry> findByDoctorIdAndStatus(Long doctorId, String status, Pageable pageable);

    @Query("""
        SELECT w FROM WaitlistEntry w
        WHERE w.status = 'WAITING'
          AND (w.doctor.id = :doctorId OR w.doctor IS NULL)
          AND (w.preferredDate IS NULL OR w.preferredDate = :date)
        ORDER BY w.createdAt ASC
        """)
    List<WaitlistEntry> findEligibleForPromotion(Long doctorId, LocalDate date);

    boolean existsByPatientIdAndDoctorIdAndStatus(Long patientId, Long doctorId, String status);
}
