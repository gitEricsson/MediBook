package com.medibook.domain.patient.dto;

import com.medibook.domain.patient.entity.PatientProfile;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class PatientProfileResponse {

    private Long id;
    private Long userId;
    private String bloodGroup;
    private String allergies;
    private String medicalHistory;
    private String emergencyContact;
    private String ssnMasked;  // Only last 4 digits exposed: ****-**-1234

    public static PatientProfileResponse fromEntity(PatientProfile p) {
        return PatientProfileResponse.builder()
                .id(p.getId())
                .userId(p.getUser().getId())
                .bloodGroup(p.getBloodGroup())
                .allergies(p.getAllergiesEnc())
                .medicalHistory(p.getMedicalHistoryEnc())
                .emergencyContact(p.getEmergencyContact())
                .ssnMasked(maskSsn(p.getSsnEnc()))
                .build();
    }

    private static String maskSsn(String ssn) {
        if (ssn == null || ssn.isBlank()) return null;
        if (ssn.length() <= 4) return "****";
        return "****-**-" + ssn.substring(ssn.length() - 4);
    }
}
