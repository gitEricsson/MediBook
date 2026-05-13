package com.medibook.domain.schedule.repository;

import com.medibook.domain.schedule.entity.DoctorLeave;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface DoctorLeaveRepository extends JpaRepository<DoctorLeave, Long> {

    List<DoctorLeave> findByDoctorId(Long doctorId);

    @Query("""
        SELECT l FROM DoctorLeave l
        WHERE l.doctor.id = :doctorId
          AND l.status = 'APPROVED'
          AND l.startDate <= :date AND l.endDate >= :date
        """)
    List<DoctorLeave> findActiveLeaveOnDate(Long doctorId, LocalDate date);
}
