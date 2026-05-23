package com.medibook.domain.survey.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.survey.dto.SurveyRequest;
import com.medibook.domain.survey.dto.SurveyResponse;
import com.medibook.domain.survey.entity.ConsultationSurvey;
import com.medibook.domain.survey.repository.ConsultationSurveyRepository;
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

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class SurveyService {

    private final ConsultationSurveyRepository surveyRepository;
    private final AppointmentRepository        appointmentRepository;
    private final UserRepository               userRepository;

    @Transactional
    public void submitSurvey(SurveyRequest req, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(req.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", req.getAppointmentId()));

        if (!appointment.getPatient().getId().equals(principal.getId())) {
            throw new MediBookException("You can only submit feedback for your own appointments",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new MediBookException("Feedback can only be submitted for completed appointments",
                    HttpStatus.BAD_REQUEST, "APPOINTMENT_NOT_COMPLETED");
        }
        if (surveyRepository.existsByAppointmentId(req.getAppointmentId())) {
            throw new MediBookException("Feedback has already been submitted for this appointment",
                    HttpStatus.CONFLICT, "SURVEY_ALREADY_SUBMITTED");
        }

        User patient = userRepository.getReferenceById(principal.getId());

        ConsultationSurvey survey = ConsultationSurvey.builder()
                .appointment(appointment)
                .patient(patient)
                .doctor(appointment.getDoctor())
                .overallSatisfaction(req.getOverallSatisfaction().byteValue())
                .communicationQuality(req.getCommunicationQuality().byteValue())
                .waitTimeExperience(req.getWaitTimeExperience().byteValue())
                .recommendLikelihood(req.getRecommendLikelihood().byteValue())
                .privateComments(req.getPrivateComments())
                .submittedAt(LocalDateTime.now())
                .build();

        surveyRepository.save(survey);
        log.info("Survey submitted for appointment [{}] by patient [{}]",
                req.getAppointmentId(), principal.getId());
    }

    /** True if the patient has already submitted feedback for this appointment. */
    @Transactional(readOnly = true)
    public boolean hasSurveyForAppointment(Long appointmentId, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", appointmentId));
        if (!appointment.getPatient().getId().equals(principal.getId())) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return surveyRepository.existsByAppointmentId(appointmentId);
    }

    /** Admin — all surveys paginated, newest first. */
    @Transactional(readOnly = true)
    public Page<SurveyResponse> getAllSurveys(Pageable pageable) {
        return surveyRepository.findAllWithDetails(pageable).map(SurveyResponse::fromEntity);
    }

    /** Admin — surveys for a specific doctor, newest first. */
    @Transactional(readOnly = true)
    public Page<SurveyResponse> getSurveysForDoctor(Long doctorId, Pageable pageable) {
        return surveyRepository.findByDoctorIdWithDetails(doctorId, pageable).map(SurveyResponse::fromEntity);
    }
}
