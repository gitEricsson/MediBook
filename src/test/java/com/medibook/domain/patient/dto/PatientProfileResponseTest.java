package com.medibook.domain.patient.dto;

import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.user.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PatientProfileResponse")
class PatientProfileResponseTest {

    @Test
    void fromEntityMasksSsnAndMapsPatientProfileFields() {
        PatientProfile profile = PatientProfile.builder()
                .id(11L)
                .user(User.builder().id(22L).build())
                .bloodGroup("O+")
                .allergiesEnc("peanuts")
                .medicalHistoryEnc("asthma")
                .emergencyContact("Jane Doe")
                .ssnEnc("123-45-6789")
                .build();

        PatientProfileResponse response = PatientProfileResponse.fromEntity(profile);

        assertThat(response.getId()).isEqualTo(11L);
        assertThat(response.getUserId()).isEqualTo(22L);
        assertThat(response.getBloodGroup()).isEqualTo("O+");
        assertThat(response.getAllergies()).isEqualTo("peanuts");
        assertThat(response.getMedicalHistory()).isEqualTo("asthma");
        assertThat(response.getEmergencyContact()).isEqualTo("Jane Doe");
        assertThat(response.getSsnMasked()).isEqualTo("****-**-6789");
    }

    @Test
    void fromEntityDoesNotExposeShortOrBlankSsnValues() {
        PatientProfile shortSsn = PatientProfile.builder()
                .user(User.builder().id(1L).build())
                .ssnEnc("123")
                .build();
        PatientProfile blankSsn = PatientProfile.builder()
                .user(User.builder().id(1L).build())
                .ssnEnc(" ")
                .build();

        assertThat(PatientProfileResponse.fromEntity(shortSsn).getSsnMasked()).isEqualTo("****");
        assertThat(PatientProfileResponse.fromEntity(blankSsn).getSsnMasked()).isNull();
    }
}
