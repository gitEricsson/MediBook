package com.medibook.domain.copilot.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.ai.client.AiChatClient;
import com.medibook.ai.client.AiChatResponse;
import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.consultation.dto.ConsultationNoteRequest;
import com.medibook.domain.consultation.dto.ConsultationNoteResponse;
import com.medibook.domain.consultation.service.ConsultationNoteService;
import com.medibook.domain.copilot.dto.CopilotDtos.*;
import com.medibook.domain.copilot.entity.VisitCopilotSession;
import com.medibook.domain.copilot.repository.VisitCopilotRepository;
import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Visit Co-Pilot — live in-call AI assistant for doctors.
 *
 * <p>During a telemedicine call the doctor's browser streams a rolling transcript
 * (captured via the Web Speech API or a third-party STT). This service keeps the
 * transcript in MySQL, asks Claude for a structured brief on demand, and on
 * finalize converts the doctor-approved brief into a consultation note.
 *
 * <p>The transcript and brief are persisted with PHI encryption (same converter as
 * consultation_notes), so this is safe to write to durable storage.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VisitCopilotService {

    private static final String BRIEF_SYSTEM_PROMPT = """
            You are MediBook Visit Co-Pilot, a clinical assistant supporting the doctor during a live
            telemedicine consultation. You receive a rolling transcript of the conversation and must
            produce a structured brief that the doctor reviews before saving as a consultation note.

            Hard rules:
            - You are an assistant, not the clinician. Frame everything as suggestions for the doctor.
            - NEVER fabricate symptoms, medications, allergies, or history that are not in the transcript.
            - Be concise. Prefer bullet phrasing over prose. Avoid disclaimers in every field.
            - Flag red flags conservatively but unambiguously (e.g. "chest pain + dyspnea — rule out ACS/PE").
            - ICD-10 codes must be plausible for what is in the transcript; include the code and short name.
            - Rx drafts are suggestions only. Include drug, dose, route, frequency, duration.

            Output ONLY a single JSON object with this exact shape (no markdown, no commentary):
            {
              "chief_complaint": string,
              "hpi": string,
              "red_flags": [string],
              "differentials": [string],
              "suggested_icd": [string],
              "rx_draft": [string],
              "soap": {
                "subjective": string,
                "objective": string,
                "assessment": string,
                "plan": string
              }
            }
            If the transcript is too short to populate a field, return an empty string or empty array
            for that field — do not invent content.
            """;

    private final VisitCopilotRepository copilotRepository;
    private final TelemedicineSessionRepository telemedicineRepository;
    private final ConsultationNoteService consultationNoteService;
    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;

    @Transactional
    public CopilotStateDto startOrGet(Long telemedicineSessionId, UserPrincipal principal) {
        TelemedicineSession ts = loadSessionAndAuthorize(telemedicineSessionId, principal);
        VisitCopilotSession session = copilotRepository.findByTelemedicineSessionId(telemedicineSessionId)
                .orElseGet(() -> copilotRepository.save(VisitCopilotSession.builder()
                        .telemedicineSessionId(telemedicineSessionId)
                        .appointmentId(ts.getAppointment().getId())
                        .doctorId(ts.getAppointment().getDoctor().getId())
                        .patientId(ts.getAppointment().getPatient().getId())
                        .build()));
        return toDto(session);
    }

    @Transactional
    public CopilotStateDto appendTranscript(Long copilotId, AppendTranscriptRequest req, UserPrincipal principal) {
        VisitCopilotSession session = loadCopilotAndAuthorize(copilotId, principal);
        if (req.chunk() == null || req.chunk().isBlank()) {
            return toDto(session);
        }
        String existing  = session.getTranscript() == null ? "" : session.getTranscript();
        String separator = existing.isEmpty() || existing.endsWith("\n") ? "" : " ";
        session.setTranscript(existing + separator + req.chunk().trim());
        return toDto(copilotRepository.save(session));
    }

    @Transactional
    public CopilotStateDto refreshBrief(Long copilotId, UserPrincipal principal) {
        VisitCopilotSession session = loadCopilotAndAuthorize(copilotId, principal);
        String transcript = session.getTranscript();
        if (transcript == null || transcript.isBlank()) {
            return toDto(session);
        }

        AiChatResponse ai = aiChatClient.complete(BRIEF_SYSTEM_PROMPT, transcript, 900);
        String raw = ai == null ? "" : (ai.getText() == null ? "" : ai.getText().trim());
        String json = extractJson(raw);
        if (json == null) {
            log.warn("Co-Pilot brief skipped — model did not return JSON. raw: {}",
                    raw.length() > 200 ? raw.substring(0, 200) + "…" : raw);
            return toDto(session);
        }

        session.setBriefJson(json);
        session.setRedFlags(extractRedFlagsLine(json));
        return toDto(copilotRepository.save(session));
    }

    @Transactional(readOnly = true)
    public CopilotStateDto getState(Long copilotId, UserPrincipal principal) {
        return toDto(loadCopilotAndAuthorize(copilotId, principal));
    }

    @Transactional
    public CopilotStateDto finalize(Long copilotId, FinalizeRequest req, UserPrincipal principal) {
        VisitCopilotSession session = loadCopilotAndAuthorize(copilotId, principal);
        if (session.isFinalized()) {
            throw new MediBookException("Co-Pilot session already finalized",
                    HttpStatus.CONFLICT, "COPILOT_ALREADY_FINALIZED");
        }

        ConsultationNoteRequest noteReq = new ConsultationNoteRequest();
        noteReq.setDiagnosis(req.diagnosis());
        noteReq.setTreatmentPlan(req.treatmentPlan());
        noteReq.setPrescriptions(req.prescriptions());
        if (req.followUpDate() != null && !req.followUpDate().isBlank()) {
            try {
                noteReq.setFollowUpDate(LocalDate.parse(req.followUpDate()));
            } catch (Exception ignored) { /* leave null */ }
        }

        ConsultationNoteResponse note =
                consultationNoteService.create(session.getAppointmentId(), noteReq, principal);

        session.setFinalized(true);
        session.setFinalizedAt(LocalDateTime.now());
        session.setConsultationNoteId(note.getId());
        return toDto(copilotRepository.save(session));
    }

    private TelemedicineSession loadSessionAndAuthorize(Long id, UserPrincipal principal) {
        TelemedicineSession ts = telemedicineRepository.findByIdWithDetails(id)
                .orElseThrow(() -> new ResourceNotFoundException("TelemedicineSession", "id", id));
        // Only the doctor on the appointment may use the co-pilot.
        Long doctorUserId = ts.getAppointment().getDoctor().getUser().getId();
        if (!doctorUserId.equals(principal.getId()) && !principal.hasRole("ROLE_ADMIN")) {
            throw new MediBookException("Not authorized for this telemedicine session",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }
        return ts;
    }

    private VisitCopilotSession loadCopilotAndAuthorize(Long copilotId, UserPrincipal principal) {
        VisitCopilotSession session = copilotRepository.findById(copilotId)
                .orElseThrow(() -> new ResourceNotFoundException("CopilotSession", "id", copilotId));
        // We trust the telemedicine session's doctor as the source of truth; re-check via that path.
        loadSessionAndAuthorize(session.getTelemedicineSessionId(), principal);
        return session;
    }

    private CopilotStateDto toDto(VisitCopilotSession s) {
        return new CopilotStateDto(
                s.getId(),
                s.getTelemedicineSessionId(),
                s.getAppointmentId(),
                s.getDoctorId(),
                s.getPatientId(),
                s.getTranscript(),
                parseBrief(s.getBriefJson()),
                s.getRedFlags(),
                s.isFinalized(),
                s.getFinalizedAt(),
                s.getConsultationNoteId(),
                s.getUpdatedAt()
        );
    }

    private BriefDto parseBrief(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> redFlags     = stringArray(root.path("red_flags"));
            List<String> differentials = stringArray(root.path("differentials"));
            List<String> icd          = stringArray(root.path("suggested_icd"));
            List<String> rx           = stringArray(root.path("rx_draft"));
            JsonNode soap             = root.path("soap");
            SoapDto soapDto = new SoapDto(
                    soap.path("subjective").asText(""),
                    soap.path("objective").asText(""),
                    soap.path("assessment").asText(""),
                    soap.path("plan").asText("")
            );
            return new BriefDto(
                    root.path("chief_complaint").asText(""),
                    root.path("hpi").asText(""),
                    redFlags,
                    differentials,
                    icd,
                    rx,
                    soapDto
            );
        } catch (Exception ex) {
            log.warn("Co-Pilot: stored brief JSON failed to parse: {}", ex.getMessage());
            return null;
        }
    }

    /** Pull the first JSON object out of the model output, in case it wraps in code fences. */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end   = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String slice = raw.substring(start, end + 1);
        try {
            objectMapper.readTree(slice);
            return slice;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Compact one-liner for the FE banner. */
    private String extractRedFlagsLine(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            List<String> flags = stringArray(root.path("red_flags"));
            return flags.isEmpty() ? null : String.join(" • ", flags);
        } catch (Exception ex) {
            return null;
        }
    }

    private List<String> stringArray(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(n -> { if (n.isTextual()) out.add(n.asText()); });
        }
        return out;
    }
}
