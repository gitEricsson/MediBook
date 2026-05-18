package com.medibook.domain.prescription.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.medibook.domain.prescription.entity.Prescription;
import com.medibook.domain.prescription.entity.PrescriptionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.time.LocalDateTime;

public class PrescriptionDtos {

    public record CreateRequest(
            @NotNull   Long appointmentId,
            @NotBlank  String drugName,
            @NotBlank  String dosage,
            String route,
            @NotBlank  String frequency,
            @Positive  Integer durationDays,
            String instructions
    ) {}

    public record UpdateRequest(
            String dosage,
            String route,
            String frequency,
            Integer durationDays,
            String instructions
    ) {}

    public record CancelRequest(String reason) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            Long id,
            Long appointmentId,
            Long doctorId,
            String doctorName,
            Long patientId,
            String drugName,
            String dosage,
            String route,
            String frequency,
            Integer durationDays,
            String instructions,
            PrescriptionStatus status,
            LocalDateTime issuedAt,
            LocalDateTime expiresAt,
            LocalDateTime cancelledAt,
            String cancelledReason
    ) {
        public static Response from(Prescription p) {
            String docName = p.getDoctor() != null && p.getDoctor().getUser() != null
                    ? p.getDoctor().getUser().getFullName() : null;
            return new Response(
                    p.getId(),
                    p.getAppointment() != null ? p.getAppointment().getId() : null,
                    p.getDoctor() != null ? p.getDoctor().getId() : null,
                    docName,
                    p.getPatient() != null ? p.getPatient().getId() : null,
                    p.getDrugName(),
                    p.getDosage(),
                    p.getRoute(),
                    p.getFrequency(),
                    p.getDurationDays(),
                    p.getInstructions(),
                    p.getStatus(),
                    p.getIssuedAt(),
                    p.getExpiresAt(),
                    p.getCancelledAt(),
                    p.getCancelledReason()
            );
        }
    }
}
