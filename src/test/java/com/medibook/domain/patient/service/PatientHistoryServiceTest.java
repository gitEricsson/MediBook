package com.medibook.domain.patient.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.patient.dto.PatientSummaryResponse;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.patient.repository.PatientProfileRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("PatientHistoryService — Unit Tests")
class PatientHistoryServiceTest {

    @Mock UserRepository             userRepository;
    @Mock PatientProfileRepository   profileRepository;
    @Mock ConsultationNoteRepository consultationNoteRepository;

    @InjectMocks PatientHistoryService patientHistoryService;

    private User          patient;
    private PatientProfile profile;

    @BeforeEach
    void setUp() {
        patient = User.builder()
                .id(1L).email("alice@test.com")
                .firstName("Alice").lastName("Patient")
                .role(Role.ROLE_PATIENT)
                .dateOfBirth(LocalDate.of(1990, 5, 15))
                .build();

        profile = PatientProfile.builder()
                .id(1L).user(patient)
                .bloodGroup("O+")
                .allergiesEnc("Penicillin")       // stored encrypted, returned as-is by service
                .medicalHistoryEnc("Type 2 Diabetes")
                .build();
    }


    @Test
    @DisplayName("getPatientSummary — unknown patientId throws NOT_FOUND")
    void getPatientSummary_userNotFound_throwsNotFound() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> patientHistoryService.getPatientSummary(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(profileRepository);
        verifyNoInteractions(consultationNoteRepository);
    }


    @Test
    @DisplayName("getPatientSummary — always includes patientId, fullName, dateOfBirth from User")
    void getPatientSummary_alwaysIncludesUserFields() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of());

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getPatientId()).isEqualTo(1L);
        assertThat(response.getFullName()).isEqualTo("Alice Patient");
        assertThat(response.getDateOfBirth()).isEqualTo(LocalDate.of(1990, 5, 15));
    }


    @Test
    @DisplayName("getPatientSummary — with profile populates bloodGroup, allergies, medicalHistory")
    void getPatientSummary_withProfile_includesAllProfileFields() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of());

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getBloodGroup()).isEqualTo("O+");
        assertThat(response.getAllergies()).isEqualTo("Penicillin");
        assertThat(response.getMedicalHistory()).isEqualTo("Type 2 Diabetes");
    }

    @Test
    @DisplayName("getPatientSummary — without profile all PHI fields are null")
    void getPatientSummary_withoutProfile_phiFieldsAreNull() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of());

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getBloodGroup()).isNull();
        assertThat(response.getAllergies()).isNull();
        assertThat(response.getMedicalHistory()).isNull();
    }


    @Test
    @DisplayName("getPatientSummary — with notes uses first entry (most recent) for lastVisit fields")
    void getPatientSummary_withNotes_usesFirstNoteAsLastVisit() {
        ConsultationNote recent = mock(ConsultationNote.class);
        when(recent.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 4, 10, 14, 30, 0));
        when(recent.getDiagnosis()).thenReturn("Hypertension Stage 2");

        ConsultationNote older = mock(ConsultationNote.class);
        // older would appear second in the list (repository returns DESC order)

        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of(recent, older));

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getLastVisitDate()).isEqualTo("2026-04-10");   // ISO_LOCAL_DATE format
        assertThat(response.getLastVisitDiagnosis()).isEqualTo("Hypertension Stage 2");
        verify(older, never()).getDiagnosis();  // older note never accessed
    }

    @Test
    @DisplayName("getPatientSummary — lastVisitDate is formatted as ISO_LOCAL_DATE (YYYY-MM-DD)")
    void getPatientSummary_lastVisitDateFormattedCorrectly() {
        ConsultationNote note = mock(ConsultationNote.class);
        when(note.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 1, 5, 9, 0, 0));
        when(note.getDiagnosis()).thenReturn("Flu");

        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of(note));

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        // Must be zero-padded YYYY-MM-DD, not "2026-1-5"
        assertThat(response.getLastVisitDate()).isEqualTo("2026-01-05");
    }

    @Test
    @DisplayName("getPatientSummary — no consultation history → lastVisit fields are null")
    void getPatientSummary_noNotes_lastVisitFieldsAreNull() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.empty());
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of());

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getLastVisitDate()).isNull();
        assertThat(response.getLastVisitDiagnosis()).isNull();
    }


    @Test
    @DisplayName("getPatientSummary — all sources present yields fully populated response")
    void getPatientSummary_allSourcesPresent_returnsFullResponse() {
        ConsultationNote note = mock(ConsultationNote.class);
        when(note.getCreatedAt()).thenReturn(LocalDateTime.of(2026, 6, 1, 10, 0, 0));
        when(note.getDiagnosis()).thenReturn("Type 2 Diabetes");

        when(userRepository.findById(1L)).thenReturn(Optional.of(patient));
        when(profileRepository.findByUserId(1L)).thenReturn(Optional.of(profile));
        when(consultationNoteRepository.findByPatientId(1L)).thenReturn(List.of(note));

        PatientSummaryResponse response = patientHistoryService.getPatientSummary(1L);

        assertThat(response.getPatientId()).isEqualTo(1L);
        assertThat(response.getFullName()).isEqualTo("Alice Patient");
        assertThat(response.getBloodGroup()).isEqualTo("O+");
        assertThat(response.getAllergies()).isEqualTo("Penicillin");
        assertThat(response.getMedicalHistory()).isEqualTo("Type 2 Diabetes");
        assertThat(response.getLastVisitDate()).isEqualTo("2026-06-01");
        assertThat(response.getLastVisitDiagnosis()).isEqualTo("Type 2 Diabetes");
    }
}
