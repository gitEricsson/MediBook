package com.medibook.domain.schedule.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.schedule.dto.AppointmentSeriesResponse;
import com.medibook.domain.schedule.dto.RecurringAppointmentRequest;
import com.medibook.domain.schedule.entity.AppointmentSeries;
import com.medibook.domain.schedule.repository.AppointmentSeriesRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecurringAppointmentService {

    private final AppointmentSeriesRepository seriesRepository;
    private final AppointmentRepository       appointmentRepository;
    private final DoctorRepository            doctorRepository;
    private final UserRepository              userRepository;

    @Transactional
    public AppointmentSeriesResponse createSeries(RecurringAppointmentRequest req, UserPrincipal principal) {
        User patient = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", principal.getId()));

        Doctor doctor = doctorRepository.findByIdWithDetails(req.getDoctorId())
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", req.getDoctorId()));

        if (!doctor.isActive()) {
            throw new MediBookException("Doctor is not currently active",
                    HttpStatus.CONFLICT, "DOCTOR_INACTIVE");
        }

        AppointmentSeries series = AppointmentSeries.builder()
                .patient(patient)
                .doctor(doctor)
                .recurrenceType(req.getRecurrenceType())
                .recurrenceInterval(req.getRecurrenceInterval())
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .maxOccurrences(req.getMaxOccurrences())
                .timeOfDay(req.getTimeOfDay())
                .durationMins(req.getDurationMins())
                .appointmentType(req.getAppointmentType())
                .reason(req.getReason())
                .build();

        AppointmentSeries saved = seriesRepository.save(series);

        // Generate initial appointments
        List<Appointment> appointments = generateAppointments(saved, patient, doctor);
        if (appointments.isEmpty()) {
            throw new MediBookException("No valid appointment slots found for the given recurrence pattern",
                    HttpStatus.BAD_REQUEST, "NO_SLOTS_AVAILABLE");
        }

        appointmentRepository.saveAll(appointments);
        log.info("Recurring series [{}] created with {} appointments", saved.getId(), appointments.size());

        return AppointmentSeriesResponse.fromEntity(saved);
    }

    @Transactional
    public void cancelSeries(Long seriesId, UserPrincipal principal) {
        AppointmentSeries series = seriesRepository.findById(seriesId)
                .orElseThrow(() -> new ResourceNotFoundException("AppointmentSeries", "id", seriesId));

        if (!series.getPatient().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        series.setStatus("CANCELLED");
        seriesRepository.save(series);

        // Cancel all future appointments in the series
        List<Appointment> futureAppointments = appointmentRepository
                .findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                        series.getDoctor().getId(),
                        LocalDateTime.now(),
                        series.getEndDate() != null
                                ? series.getEndDate().plusDays(1).atStartOfDay()
                                : LocalDateTime.now().plusYears(1))
                .stream()
                .filter(a -> a.getSeries() != null && a.getSeries().getId().equals(seriesId))
                .filter(a -> a.getStatus() != AppointmentStatus.COMPLETED
                        && a.getStatus() != AppointmentStatus.CANCELLED)
                .toList();

        futureAppointments.forEach(a -> {
            a.setStatus(AppointmentStatus.CANCELLED);
            a.setCancellationReason("Series cancelled");
        });
        appointmentRepository.saveAll(futureAppointments);
        log.info("Series [{}] cancelled, {} future appointments cancelled", seriesId, futureAppointments.size());
    }

    @Transactional(readOnly = true)
    public Page<AppointmentSeriesResponse> getMySeries(UserPrincipal principal, Pageable pageable) {
        return seriesRepository.findByPatientIdAndStatus(principal.getId(), "ACTIVE", pageable)
                .map(AppointmentSeriesResponse::fromEntity);
    }

    private List<Appointment> generateAppointments(AppointmentSeries series, User patient, Doctor doctor) {
        List<Appointment> appointments = new ArrayList<>();
        LocalDate current = series.getStartDate();
        int count = 0;
        int maxOcc = series.getMaxOccurrences() != null ? series.getMaxOccurrences() : 52;
        LocalDate endDate = series.getEndDate() != null
                ? series.getEndDate()
                : current.plusYears(1);

        while (!current.isAfter(endDate) && count < maxOcc) {
            LocalDateTime scheduledAt = current.atTime(series.getTimeOfDay());
            LocalDateTime endTime = scheduledAt.plusMinutes(series.getDurationMins());

            if (scheduledAt.isAfter(LocalDateTime.now())
                    && !appointmentRepository.existsConflict(doctor.getId(), scheduledAt, endTime)) {
                Appointment appt = Appointment.builder()
                        .patient(patient)
                        .doctor(doctor)
                        .department(doctor.getDepartment())
                        .scheduledAt(scheduledAt)
                        .endTime(endTime)
                        .durationMins(series.getDurationMins())
                        .type(series.getAppointmentType())
                        .reason(series.getReason())
                        .status(AppointmentStatus.PENDING)
                        .confirmationCode("MB-S" + series.getId() + "-" + UUID.randomUUID().toString().substring(0, 4).toUpperCase())
                        .series(series)
                        .build();
                appointments.add(appt);
                count++;
            }

            current = switch (series.getRecurrenceType()) {
                case "DAILY"   -> current.plusDays(series.getRecurrenceInterval());
                case "WEEKLY"  -> current.plusWeeks(series.getRecurrenceInterval());
                case "MONTHLY" -> current.plusMonths(series.getRecurrenceInterval());
                default        -> current.plusWeeks(1);
            };
        }
        return appointments;
    }
}
