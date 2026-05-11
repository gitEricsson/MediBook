package com.medibook.domain.schedule.repository;

import com.medibook.domain.schedule.entity.AppointmentSeries;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppointmentSeriesRepository extends JpaRepository<AppointmentSeries, Long> {

    Page<AppointmentSeries> findByPatientIdAndStatus(Long patientId, String status, Pageable pageable);

    Page<AppointmentSeries> findByDoctorId(Long doctorId, Pageable pageable);
}
