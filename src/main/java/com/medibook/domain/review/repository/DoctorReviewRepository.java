package com.medibook.domain.review.repository;

import com.medibook.domain.review.entity.DoctorReview;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DoctorReviewRepository extends JpaRepository<DoctorReview, Long> {

    boolean existsByAppointmentId(Long appointmentId);

    Page<DoctorReview> findByDoctorIdAndStatus(Long doctorId, String status, Pageable pageable);

    Page<DoctorReview> findByPatientId(Long patientId, Pageable pageable);

    Page<DoctorReview> findByStatus(String status, Pageable pageable);

    @Query("SELECT AVG(r.rating) FROM DoctorReview r WHERE r.doctor.id = :doctorId AND r.status = 'APPROVED'")
    Optional<Double> findAverageRatingByDoctorId(Long doctorId);

    @Query("SELECT COUNT(r) FROM DoctorReview r WHERE r.doctor.id = :doctorId AND r.status = 'APPROVED'")
    long countApprovedByDoctorId(Long doctorId);
}
