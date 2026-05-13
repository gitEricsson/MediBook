package com.medibook.domain.schedule.repository;

import com.medibook.domain.schedule.entity.HospitalHoliday;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface HospitalHolidayRepository extends JpaRepository<HospitalHoliday, Long> {

    boolean existsByHolidayDate(LocalDate date);

    List<HospitalHoliday> findByHolidayDateBetween(LocalDate from, LocalDate to);
}
