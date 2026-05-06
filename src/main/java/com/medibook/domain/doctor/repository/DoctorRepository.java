package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.Doctor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Long> {

    Optional<Doctor> findByUserId(Long userId);

    Optional<Doctor> findByLicenseNumber(String licenseNumber);

    boolean existsByLicenseNumber(String licenseNumber);

    Page<Doctor> findByDepartmentId(Long departmentId, Pageable pageable);

    @Query("SELECT d FROM Doctor d JOIN FETCH d.user JOIN FETCH d.department WHERE d.id = :id")
    Optional<Doctor> findByIdWithDetails(Long id);
}
