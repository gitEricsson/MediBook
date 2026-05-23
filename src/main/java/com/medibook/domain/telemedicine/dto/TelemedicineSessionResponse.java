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
    private Long conversationId;
    private String doctorName;
    private TelemedicineSessionStatus status;
    private String roomId;
    private String twilioRoomSid;
    private String twilioRoomName;
    private String joinUrl;
    private LocalDateTime startedAt;
    private LocalDateTime acceptedAt;
    private LocalDateTime endedAt;
    private Integer durationSeconds;
    private boolean patientConsent;
    private boolean doctorReviewed;
    private String callNoteDraft;
    private LocalDateTime createdAt;
    /** Appointment scheduled start — used by frontend to enforce ±10 min join window. */
    private LocalDateTime appointmentScheduledAt;
    /** Appointment duration in minutes — used by frontend to compute session-end boundary. */
    private Integer appointmentDurationMins;

    public static TelemedicineSessionResponse fromEntity(TelemedicineSession s, boolean isDoctor) {
        return TelemedicineSessionResponse.builder()
                .id(s.getId())
                .appointmentId(s.getAppointment().getId())
                .patientId(s.getAppointment().getPatient().getId())
                .doctorId(s.getAppointment().getDoctor().getUser().getId())
                .conversationId(s.getChatConversationId())
                .doctorName(s.getAppointment().getDoctor().getUser().getFullName())
                .status(s.getStatus())
                .roomId(s.getRoomId())
                .twilioRoomSid(s.getTwilioRoomSid())
                .twilioRoomName(s.getTwilioRoomName())
                .joinUrl(isDoctor ? s.getJoinUrlDoctor() : s.getJoinUrlPatient())
                .startedAt(s.getStartedAt())
                .acceptedAt(s.getAcceptedAt())
                .endedAt(s.getEndedAt())
                .durationSeconds(s.getDurationSeconds())
                .patientConsent(s.isPatientConsent())
                .doctorReviewed(s.isDoctorReviewed())
                // Only expose call note draft to doctor
                .callNoteDraft(isDoctor ? s.getCallNoteDraft() : null)
                .createdAt(s.getCreatedAt())
                .appointmentScheduledAt(s.getAppointment().getScheduledAt())
                .appointmentDurationMins(s.getAppointment().getDurationMins())
                .build();
    }
}
