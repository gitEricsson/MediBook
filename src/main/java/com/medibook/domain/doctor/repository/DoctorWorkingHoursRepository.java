package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorId(Long doctorId);
    List<DoctorWorkingHours> findByDoctorIdAndDayOfWeek(Long doctorId, int dayOfWeek);
}
