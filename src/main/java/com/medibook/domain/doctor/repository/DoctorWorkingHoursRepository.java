package com.medibook.domain.doctor.repository;

import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import org.springframework.data.jpa.repository.JpaRepository;
<<<<<<< HEAD
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorId(Long doctorId);
    List<DoctorWorkingHours> findByDoctorIdAndDayOfWeek(Long doctorId, int dayOfWeek);
=======

import java.util.List;

public interface DoctorWorkingHoursRepository extends JpaRepository<DoctorWorkingHours, Long> {
    List<DoctorWorkingHours> findByDoctorIdAndDayOfWeek(Long doctorId, Integer dayOfWeek);
>>>>>>> 80871a9dabebcc9633d36d9e838c2919544c273a
}
