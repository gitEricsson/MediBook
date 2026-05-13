package com.medibook.domain.schedule.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.schedule.entity.NoteTemplate;
import com.medibook.domain.schedule.repository.NoteTemplateRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class NoteTemplateService {

    private final NoteTemplateRepository templateRepository;
    private final DoctorRepository       doctorRepository;

    @Transactional(readOnly = true)
    public List<NoteTemplate> getTemplatesForDoctor(Long doctorId) {
        return templateRepository.findAvailableForDoctor(doctorId);
    }

    @Transactional
    public NoteTemplate createTemplate(String name, String templateType, String content,
                                        Long doctorId, UserPrincipal principal) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        if (!doctor.getUser().getId().equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        NoteTemplate template = NoteTemplate.builder()
                .name(name)
                .templateType(templateType)
                .content(content)
                .doctor(doctor)
                .build();

        return templateRepository.save(template);
    }

    @Transactional
    public void seedSystemTemplates() {
        if (templateRepository.count() > 0) return;

        templateRepository.saveAll(List.of(
                NoteTemplate.builder()
                        .name("SOAP Note")
                        .templateType("SOAP")
                        .content("**Subjective:**\n[Patient's chief complaint and history]\n\n**Objective:**\n[Physical examination findings]\n\n**Assessment:**\n[Diagnosis or differential diagnoses]\n\n**Plan:**\n[Treatment plan and follow-up]")
                        .build(),
                NoteTemplate.builder()
                        .name("Diagnosis Note")
                        .templateType("DIAGNOSIS")
                        .content("**Primary Diagnosis:**\n\n**Differential Diagnoses:**\n1.\n2.\n3.\n\n**Supporting Evidence:**\n\n**Treatment:**\n\n**Medications:**\n\n**Follow-up:**")
                        .build(),
                NoteTemplate.builder()
                        .name("Follow-up Note")
                        .templateType("FOLLOWUP")
                        .content("**Previous Diagnosis:**\n\n**Current Status:**\n\n**Changes Since Last Visit:**\n\n**Current Medications:**\n\n**Adjusted Plan:**\n\n**Next Follow-up:**")
                        .build(),
                NoteTemplate.builder()
                        .name("Free Text Note")
                        .templateType("CUSTOM")
                        .content("[Enter consultation notes here]")
                        .build()
        ));
    }
}
