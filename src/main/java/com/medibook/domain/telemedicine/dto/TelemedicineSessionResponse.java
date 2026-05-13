package com.medibook.domain.telemedicine.dto;

import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class TelemedicineSessionResponse {

    private Long id;
    private Long appointmentId;
    private Long patientId;
    private Long doctorId;
    private String doctorName;
    private TelemedicineSessionStatus status;
    private String roomId;
    private String joinUrl;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private boolean patientConsent;
    private boolean doctorReviewed;
    private String callNoteDraft;
    private LocalDateTime createdAt;

    public static TelemedicineSessionResponse fromEntity(TelemedicineSession s, boolean isDoctor) {
        return TelemedicineSessionResponse.builder()
                .id(s.getId())
                .appointmentId(s.getAppointment().getId())
                .patientId(s.getAppointment().getPatient().getId())
                .doctorId(s.getAppointment().getDoctor().getId())
                .doctorName(s.getAppointment().getDoctor().getUser().getFullName())
                .status(s.getStatus())
                .roomId(s.getRoomId())
                .joinUrl(isDoctor ? s.getJoinUrlDoctor() : s.getJoinUrlPatient())
                .startedAt(s.getStartedAt())
                .endedAt(s.getEndedAt())
                .durationSeconds(s.getDurationSeconds())
                .patientConsent(s.isPatientConsent())
                .doctorReviewed(s.isDoctorReviewed())
                // Only expose call note draft to doctor
                .callNoteDraft(isDoctor ? s.getCallNoteDraft() : null)
                .createdAt(s.getCreatedAt())
                .build();
    }
}
