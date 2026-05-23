package com.medibook.domain.copilot.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.ai.client.AiChatClient;
import com.medibook.ai.client.AiChatResponse;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.consultation.service.ConsultationNoteService;
import com.medibook.domain.copilot.dto.CopilotDtos;
import com.medibook.domain.copilot.entity.VisitCopilotSession;
import com.medibook.domain.copilot.repository.VisitCopilotRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VisitCopilotService — Unit Tests")
class VisitCopilotServiceTest {

    @Mock VisitCopilotRepository copilotRepository;
    @Mock TelemedicineSessionRepository telemedicineRepository;
    @Mock ConsultationNoteService consultationNoteService;
    @Mock AiChatClient aiChatClient;
    @InjectMocks VisitCopilotService service;

    private final ObjectMapper realMapper = new ObjectMapper();
    private TelemedicineSession ts;
    private UserPrincipal doctorPrincipal;

    @BeforeEach
    void setUp() throws Exception {
        // would otherwise leave it null since it's a final field on a mocked dep tree.
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", realMapper);

        User docUser = User.builder()
                .id(99L).email("d@test.com").firstName("Bob").lastName("MD")
                .role(com.medibook.domain.user.entity.Role.ROLE_DOCTOR).enabled(true).isActive(true).build();
        Doctor doctor = Doctor.builder().id(7L).user(docUser).build();
        User patient = User.builder()
                .id(50L).email("p@test.com").firstName("Jane").lastName("Doe")
                .role(com.medibook.domain.user.entity.Role.ROLE_PATIENT).enabled(true).isActive(true).build();
        Appointment appt = Appointment.builder().id(1000L).patient(patient).doctor(doctor).build();

        ts = TelemedicineSession.builder().id(500L).appointment(appt).build();
        lenient().when(telemedicineRepository.findByIdWithDetails(500L)).thenReturn(Optional.of(ts));

        doctorPrincipal = UserPrincipal.fromUser(docUser);
    }

    @Test
    @DisplayName("startOrGet — creates a copilot session on first call")
    void startOrGet_creates() {
        when(copilotRepository.findByTelemedicineSessionId(500L)).thenReturn(Optional.empty());
        when(copilotRepository.save(any(VisitCopilotSession.class)))
                .thenAnswer(inv -> { VisitCopilotSession s = inv.getArgument(0); s.setId(1L); return s; });

        var dto = service.startOrGet(500L, doctorPrincipal);

        assertThat(dto.id()).isEqualTo(1L);
        assertThat(dto.appointmentId()).isEqualTo(1000L);
        assertThat(dto.doctorId()).isEqualTo(7L);
        assertThat(dto.patientId()).isEqualTo(50L);
    }

    @Test
    @DisplayName("auth — non-owning doctor (and non-admin) is rejected")
    void rejectsForeignDoctor() {
        User strangerUser = User.builder()
                .id(123L).email("s@test.com").firstName("Stranger").lastName("MD")
                .role(com.medibook.domain.user.entity.Role.ROLE_DOCTOR).enabled(true).isActive(true).build();
        UserPrincipal stranger = UserPrincipal.fromUser(strangerUser);

        assertThatThrownBy(() -> service.startOrGet(500L, stranger))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Not authorized");
    }

    @Test
    @DisplayName("refreshBrief — parses model JSON and pulls red flags")
    void refreshBrief_parsesJson() {
        VisitCopilotSession session = newCopilotSession();
        session.setTranscript("patient reports chest pain and dyspnea");
        when(copilotRepository.findById(1L)).thenReturn(Optional.of(session));
        when(copilotRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        String brief = """
                {"chief_complaint":"chest pain",
                 "hpi":"acute onset 2h",
                 "red_flags":["possible ACS","possible PE"],
                 "differentials":["MI","PE"],
                 "suggested_icd":["I21.9"],
                 "rx_draft":["Aspirin 300mg PO once"],
                 "soap":{"subjective":"chest pain","objective":"vitals stable","assessment":"r/o ACS","plan":"ECG, troponin"}}
                """;
        when(aiChatClient.complete(anyString(), anyString(), anyInt()))
                .thenReturn(AiChatResponse.builder().text(brief).build());

        var dto = service.refreshBrief(1L, doctorPrincipal);

        assertThat(dto.brief()).isNotNull();
        assertThat(dto.brief().redFlags()).contains("possible ACS", "possible PE");
        assertThat(dto.redFlags()).contains("possible ACS").contains("possible PE");
    }

    @Test
    @DisplayName("refreshBrief — keeps prior state when model returns non-JSON")
    void refreshBrief_skipsOnBadJson() {
        VisitCopilotSession session = newCopilotSession();
        session.setTranscript("hello");
        when(copilotRepository.findById(1L)).thenReturn(Optional.of(session));

        when(aiChatClient.complete(anyString(), anyString(), anyInt()))
                .thenReturn(AiChatResponse.builder().text("I am Claude. Here is text, not JSON.").build());

        var dto = service.refreshBrief(1L, doctorPrincipal);

        assertThat(dto.brief()).isNull();      // no brief produced
    }

    @Test
    @DisplayName("finalize — second call on a finalized session is rejected")
    void finalize_rejectsSecondCall() {
        VisitCopilotSession session = newCopilotSession();
        session.setFinalized(true);
        when(copilotRepository.findById(1L)).thenReturn(Optional.of(session));

        var req = new CopilotDtos.FinalizeRequest("dx", "plan", "rx", null);
        assertThatThrownBy(() -> service.finalize(1L, req, doctorPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("already finalized");
    }

    private VisitCopilotSession newCopilotSession() {
        VisitCopilotSession s = VisitCopilotSession.builder()
                .id(1L)
                .telemedicineSessionId(500L)
                .appointmentId(1000L)
                .doctorId(7L)
                .patientId(50L)
                .build();
        s.setCreatedAt(LocalDateTime.now());
        s.setUpdatedAt(LocalDateTime.now());
        return s;
    }
}
