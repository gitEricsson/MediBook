package com.medibook.domain.consultation.dto;

import com.medibook.domain.consultation.entity.ConsultationNote;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@Builder
public class ConsultationNoteResponse {

    private Long id;
    private Long appointmentId;
    private String patientName;
    private String doctorName;
    private String diagnosis;           // PHI — decrypted by JPA converter
    private String treatmentPlan;       // PHI — decrypted by JPA converter
    private String prescriptions;
    private LocalDate followUpDate;
    private LocalDateTime createdAt;

    public static ConsultationNoteResponse fromEntity(ConsultationNote cn) {
        return ConsultationNoteResponse.builder()
                .id(cn.getId())
                .appointmentId(cn.getAppointment().getId())
                .patientName(cn.getAppointment().getPatient().getFullName())
                .doctorName(cn.getAppointment().getDoctor().getUser().getFullName())
                .diagnosis(cn.getDiagnosis())
                .treatmentPlan(cn.getTreatmentPlan())
                .prescriptions(cn.getPrescriptions())
                .followUpDate(cn.getFollowUpDate())
                .createdAt(cn.getCreatedAt())
                .build();
    }
}
