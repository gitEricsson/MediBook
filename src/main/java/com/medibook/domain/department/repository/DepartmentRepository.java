package com.medibook.domain.department.repository;

import com.medibook.domain.department.dto.DepartmentAdminResponse;
import com.medibook.domain.department.entity.Department;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Long> {
    Optional<Department> findByNameIgnoreCase(String name);
    boolean existsByNameIgnoreCase(String name);
    boolean existsByCodeIgnoreCase(String code);

    @Query("""
        SELECT new com.medibook.domain.department.dto.DepartmentAdminResponse(
            d.id, d.name, d.code,
            COUNT(DISTINCT doc.id),
            COUNT(DISTINCT appt.id),
            d.isActive
        )
        FROM Department d
        LEFT JOIN Doctor doc ON doc.department = d AND doc.isActive = true
        LEFT JOIN Appointment appt ON appt.department = d AND appt.scheduledAt >= :startDate AND appt.status != 'CANCELLED'
        WHERE (:q IS NULL OR LOWER(d.name) LIKE LOWER(CONCAT('%', :q, '%')) OR LOWER(d.code) LIKE LOWER(CONCAT('%', :q, '%')))
          AND (:status IS NULL OR d.isActive = :status)
        GROUP BY d.id, d.name, d.code, d.isActive
    """)
    Page<DepartmentAdminResponse> getAdminStats(
            @Param("q") String q, 
            @Param("status") Boolean status, 
            @Param("startDate") LocalDateTime startDate, 
            Pageable pageable);
}
