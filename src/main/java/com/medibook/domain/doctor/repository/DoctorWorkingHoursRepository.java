package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorId(Long doctorId);
    List<DoctorWorkingHours> findByDoctorIdAndDayOfWeek(Long doctorId, Integer dayOfWeek);

    @org.springframework.data.jpa.repository.Query("""
            SELECT h FROM DoctorWorkingHours h
            WHERE h.doctor.id IN :doctorIds
            ORDER BY h.doctor.id ASC, h.dayOfWeek ASC, h.startTime ASC
            """)
    List<DoctorWorkingHours> findByDoctorIds(
            @org.springframework.data.repository.query.Param("doctorIds") java.util.Collection<Long> doctorIds);

    @org.springframework.data.jpa.repository.Query("""
            SELECT dwh FROM DoctorWorkingHours dwh
            JOIN FETCH dwh.doctor d
            JOIN FETCH d.department
            WHERE dwh.dayOfWeek = :dayOfWeek
            AND d.isActive = true
            """)
    java.util.List<DoctorWorkingHours> findByDayOfWeekForActiveDoctors(
            @org.springframework.data.repository.query.Param("dayOfWeek") int dayOfWeek);
}
