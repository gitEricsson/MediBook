package com.medibook.domain.copilot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO surface for the live Visit Co-Pilot.
 *
 * The transcript and brief flow:
 *   FE captures audio → browser Web Speech API transcript → POST chunks here.
 *   Periodically the FE asks for a refreshed brief; the backend feeds the
 *   rolling transcript to Claude and returns a structured {@link BriefDto}.
 *   On finalize, the doctor-approved brief is converted into a consultation note.
 */
public class CopilotDtos {

    /** Request to append a transcript chunk during the call. */
    public record AppendTranscriptRequest(String chunk) {}

    /** Request to finalize: persist approved brief as the consultation note. */
    public record FinalizeRequest(
            String diagnosis,
            String treatmentPlan,
            String prescriptions,
            String followUpDate
    ) {}

    /** Snapshot of the co-pilot session — what the FE renders. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CopilotStateDto(
            Long id,
            Long telemedicineSessionId,
            Long appointmentId,
            Long doctorId,
            Long patientId,
            String transcript,
            BriefDto brief,
            String redFlags,
            boolean finalized,
            LocalDateTime finalizedAt,
            Long consultationNoteId,
            LocalDateTime updatedAt
    ) {}

    /** Structured AI brief generated from the rolling transcript. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BriefDto(
            String chiefComplaint,
            String hpi,
            List<String> redFlags,
            List<String> differentials,
            List<String> suggestedIcd,
            List<String> rxDraft,
            SoapDto soap
    ) {}

    public record SoapDto(String subjective, String objective, String assessment, String plan) {}
}
