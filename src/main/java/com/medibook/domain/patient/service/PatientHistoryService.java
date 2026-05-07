package com.medibook.domain.patient.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.patient.dto.PatientSummaryResponse;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.patient.repository.PatientProfileRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
@RequiredArgsConstructor
public class PatientHistoryService {

    private final UserRepository userRepository;
    private final PatientProfileRepository profileRepository;
    private final ConsultationNoteRepository consultationNoteRepository;

    @Transactional(readOnly = true)
    public PatientSummaryResponse getPatientSummary(Long patientId) {
        User user = userRepository.findById(patientId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", patientId));
                
        PatientProfile profile = profileRepository.findByUserId(patientId).orElse(null);
        
        // Find most recent consultation note
        List<ConsultationNote> notes = consultationNoteRepository.findByPatientId(patientId);
        ConsultationNote lastNote = notes.isEmpty() ? null : notes.get(0);

        PatientSummaryResponse.PatientSummaryResponseBuilder builder = PatientSummaryResponse.builder()
                .patientId(user.getId())
                .fullName(user.getFullName())
                .dateOfBirth(user.getDateOfBirth());
                
        if (profile != null) {
            builder.bloodGroup(profile.getBloodGroup())
                   .allergies(profile.getAllergiesEnc()) // Note: Service layer handles decryption via JPA Converter
                   .medicalHistory(profile.getMedicalHistoryEnc());
        }
        
        if (lastNote != null) {
            builder.lastVisitDate(lastNote.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE))
                   .lastVisitDiagnosis(lastNote.getDiagnosis());
        }

        return builder.build();
    }
}
