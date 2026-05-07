package com.medibook.domain.patient.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.patient.dto.PatientProfileRequest;
import com.medibook.domain.patient.dto.PatientProfileResponse;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.patient.repository.PatientProfileRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PatientProfileService {

    private final PatientProfileRepository profileRepository;
    private final UserRepository           userRepository;

    @Transactional(readOnly = true)
    public PatientProfileResponse getByUserId(Long userId) {
        PatientProfile profile = profileRepository.findByUserId(userId)
                .orElseThrow(() -> new ResourceNotFoundException("PatientProfile", "userId", userId));
        return PatientProfileResponse.fromEntity(profile);
    }

    /**
     * Upsert — creates the profile on first call, updates it on subsequent calls.
     * Only non-null fields in the request are applied to avoid accidental overwrites.
     */
    @Transactional
    public PatientProfileResponse upsert(Long userId, PatientProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        PatientProfile profile = profileRepository.findByUserId(userId)
                .orElseGet(() -> PatientProfile.builder().user(user).build());

        if (request.getBloodGroup()       != null) profile.setBloodGroup(request.getBloodGroup());
        if (request.getAllergies()         != null) profile.setAllergiesEnc(request.getAllergies());
        if (request.getMedicalHistory()   != null) profile.setMedicalHistoryEnc(request.getMedicalHistory());
        if (request.getEmergencyContact() != null) profile.setEmergencyContact(request.getEmergencyContact());
        if (request.getSsn()              != null) profile.setSsnEnc(request.getSsn());

        return PatientProfileResponse.fromEntity(profileRepository.save(profile));
    }
}
