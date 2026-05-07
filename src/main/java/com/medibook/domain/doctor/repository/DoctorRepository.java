package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.Doctor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Long>, JpaSpecificationExecutor<Doctor> {

    Optional<Doctor> findByUserId(Long userId);

    Optional<Doctor> findByLicenseNumber(String licenseNumber);

    boolean existsByLicenseNumber(String licenseNumber);

    Page<Doctor> findByDepartmentId(Long departmentId, Pageable pageable);

    @Query("SELECT d FROM Doctor d JOIN FETCH d.user JOIN FETCH d.department WHERE d.id = :id")
    Optional<Doctor> findByIdWithDetails(Long id);

    /**
     * Full-text search against the denormalized search_vector column.
     * Uses MySQL BOOLEAN MODE so callers can append "*" for prefix matching.
     * Returns only active doctors; limit capped at 200 to bound result set size.
     */
    @Query(value = "SELECT id FROM doctors WHERE MATCH(search_vector) AGAINST(:query IN BOOLEAN MODE) AND is_active = true LIMIT 200",
           nativeQuery = true)
    List<Long> findIdsByFullText(@Param("query") String query);
}
