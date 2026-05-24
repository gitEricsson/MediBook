package com.medibook.domain.emergency.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.appointment.service.AppointmentPricingService;
import com.medibook.domain.doctor.service.DoctorScheduleService;
import com.medibook.domain.emergency.dto.EmergencyConsultationRequest;
import com.medibook.domain.emergency.dto.EmergencyConsultationResponse;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.domain.telemedicine.dto.VideoCallResponse;
import com.medibook.domain.telemedicine.service.TelemedicineCallService;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.infrastructure.metrics.EmergencyMetrics;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmergencyConsultationService {

    private static final int EMERGENCY_SLOT_MINS = 60;

    private final AppointmentRepository   appointmentRepository;
    private final DoctorRepository        doctorRepository;
    private final UserRepository          userRepository;
    private final AppointmentEventProducer eventProducer;
    private final EmergencyMetrics        emergencyMetrics;
    private final DoctorScheduleService   doctorScheduleService;
    private final TelemedicineCallService telemedicineCallService;
    private final AppointmentPricingService pricingService;
    private final NotificationService     notificationService;

    /**
     * Request an emergency consultation.
     *
     * Flow:
     *  1. Enforce no unresolved emergency debt (unless criticalOverride).
     *  2. Find least-loaded available doctor (optionally in requested department).
     *  3. Create a live EMERGENCY appointment; settlement is generated after completion.
     *  4. Publish event so notification service pages the assigned doctor.
     */
    @Transactional
    public EmergencyConsultationResponse requestEmergency(EmergencyConsultationRequest request,
                                                          UserPrincipal principal) {
        emergencyMetrics.recordEmergencyRequest();

        User patient = userRepository.findById(principal.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", principal.getId()));

        if (!request.isCriticalOverride()) {
            try {
                enforceNoOutstandingDebt(patient.getId());
            } catch (MediBookException e) {
                emergencyMetrics.recordOutstandingDebtBlock();
                throw e;
            }
        }

        ConsultationMedium medium = request.getMedium() != null ? request.getMedium() : ConsultationMedium.VIDEO;

        Doctor doctor;
        try {
            doctor = emergencyMetrics.timeAssignment(() -> assignDoctor(request.getDepartmentId(), medium));
        } catch (MediBookException e) {
            emergencyMetrics.recordNoDoctorAvailable();
            throw e;
        } catch (Exception e) {
            emergencyMetrics.recordNoDoctorAvailable();
            throw new MediBookException("Failed to assign doctor", HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_DOCTOR");
        }

        LocalDateTime now = LocalDateTime.now();
        String confirmationCode = "EMG-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();

        java.math.BigDecimal consultationFee = pricingService.computeFee(doctor, AppointmentType.EMERGENCY, medium);

        Appointment appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .department(doctor.getDepartment())
                .scheduledAt(now)
                .endTime(now.plusMinutes(EMERGENCY_SLOT_MINS))
                .durationMins(EMERGENCY_SLOT_MINS)
                .reason(request.getSymptoms())
                .type(AppointmentType.EMERGENCY)
                .consultationMedium(medium)
                .consultationType(AppointmentType.EMERGENCY)
                .status(AppointmentStatus.IN_CONSULTATION)
                .confirmationCode(confirmationCode)
                .consultationFee(consultationFee)
                .build();

        appointment = appointmentRepository.save(appointment);

        // Evict the slot cache so the assigned doctor no longer appears available
        // for regular bookings during the emergency slot period.
        doctorScheduleService.evictSlotCache(doctor.getId(), appointment.getScheduledAt().toLocalDate());

        AppointmentEvent emergencyEvent = buildEmergencyEvent(appointment, "EMERGENCY_CONSULTATION_REQUESTED");
        eventProducer.publishAppointmentEvent(emergencyEvent);

        // Emergencies are too critical to depend solely on Kafka delivery. If the
        // consumer is lagging, paused, or the listener is mis-configured, the doctor
        // would never know. Dispatching the notification inline guarantees the
        // doctor's in-app banner + email fire as part of this request; the Kafka
        // path remains for downstream analytics and retry semantics.
        try {
            notificationService.sendEmergencyConsultationRequested(emergencyEvent);
        } catch (Exception ex) {
            log.warn("Inline emergency notification failed for appointment [{}] — Kafka path will retry: {}",
                    appointment.getId(), ex.getMessage());
        }

        log.info("Emergency consultation [{}] assigned to doctor [{}] for patient [{}]",
                appointment.getId(), doctor.getId(), patient.getId());

        // Pre-create the telemedicine session for VIDEO/AUDIO so the patient can join immediately.
        // Wrapped in try-catch: a Twilio failure must not abort the emergency consultation itself.
        Long sessionId = null;
        if (medium != ConsultationMedium.PHYSICAL) {
            try {
                VideoCallResponse callResponse = telemedicineCallService.startVideoCall(appointment.getId(), principal);
                sessionId = callResponse.sessionId();
            } catch (Exception ex) {
                log.warn("Could not pre-create telemedicine session for emergency appointment [{}]: {}",
                        appointment.getId(), ex.getMessage());
            }
        }

        return EmergencyConsultationResponse.builder()
                .appointmentId(appointment.getId())
                .doctorId(doctor.getId())
                .doctorName(doctor.getUser().getFullName())
                .departmentName(doctor.getDepartment().getName())
                .status(appointment.getStatus())
                .medium(appointment.getConsultationMedium())
                .confirmationCode(confirmationCode)
                .sessionId(sessionId)
                .consultationFee(consultationFee)
                .createdAt(now)
                .message("Emergency consultation initiated. The assigned doctor has been notified.")
                .build();
    }

    /**
     * Assign the least-loaded available doctor using a single batch count query to avoid N+1.
     * Workload = IN_CONSULTATION + CONFIRMED + EMERGENCY_PENDING_SETTLEMENT appointments today.
     */
    private Doctor assignDoctor(Long preferredDepartmentId, ConsultationMedium medium) {
        List<Doctor> candidates = preferredDepartmentId != null
                ? doctorRepository.findByDepartmentId(preferredDepartmentId,
                        org.springframework.data.domain.Pageable.unpaged()).getContent()
                : doctorRepository.findAll();

        List<Doctor> eligible = candidates.stream()
                .filter(Doctor::isActive)
                .filter(Doctor::isAcceptingNew)
                .toList();

        if (eligible.isEmpty()) {
            throw new MediBookException(
                    "No available doctors at this time. Please try again shortly or call emergency services.",
                    HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_DOCTOR");
        }

        LocalDateTime dayStart = LocalDateTime.now().toLocalDate().atStartOfDay();
        LocalDateTime dayEnd   = dayStart.plusDays(1);

        List<Long> doctorIds = eligible.stream().map(Doctor::getId).toList();

        // Single query: [doctorId, activeCount] for all candidates
        java.util.Map<Long, Long> workloadMap = new java.util.HashMap<>();
        appointmentRepository.countActiveAppointmentsByDoctorIds(doctorIds, dayStart, dayEnd)
                .forEach(row -> workloadMap.put((Long) row[0], (Long) row[1]));

        return eligible.stream()
                .min(java.util.Comparator.comparingLong(d ->
                        workloadMap.getOrDefault(d.getId(), 0L)))
                .orElseThrow(() -> new MediBookException(
                        "No available doctors at this time. Please try again shortly or call emergency services.",
                        HttpStatus.SERVICE_UNAVAILABLE, "NO_AVAILABLE_DOCTOR"));
    }

    /**
     * Enforce outstanding emergency debt: a patient with an unsettled
     * EMERGENCY_PENDING_SETTLEMENT appointment cannot request another unless
     * criticalOverride is true.
     */
    private void enforceNoOutstandingDebt(Long patientId) {
        boolean hasDebt = appointmentRepository.existsUnresolvedEmergencyDebt(patientId);
        if (hasDebt) {
            throw new MediBookException(
                    "You have an outstanding balance from a previous emergency consultation. "
                            + "Please settle the balance before requesting a new emergency consultation.",
                    HttpStatus.PAYMENT_REQUIRED, "OUTSTANDING_EMERGENCY_DEBT");
        }
    }

    private AppointmentEvent buildEmergencyEvent(Appointment a, String eventType) {
        return AppointmentEvent.builder()
                .eventType(eventType)
                .appointmentId(a.getId())
                .patientId(a.getPatient().getId())
                .patientEmail(a.getPatient().getEmail())
                .patientName(a.getPatient().getFullName())
                .doctorId(a.getDoctor().getUser().getId())
                .doctorEmail(a.getDoctor().getUser().getEmail())
                .doctorName(a.getDoctor().getUser().getFullName())
                .departmentName(a.getDoctor().getDepartment().getName())
                .scheduledAt(a.getScheduledAt())
                .status(a.getStatus())
                .build();
    }
}
