package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorIdAndDayOfWeek(Long doctorId, Integer dayOfWeek);
}
