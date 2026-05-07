package com.medibook.domain.consultation.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.dto.ConsultationNoteRequest;
import com.medibook.domain.consultation.dto.ConsultationNoteResponse;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConsultationNoteService — Unit Tests")
class ConsultationNoteServiceTest {

    @Mock ConsultationNoteRepository noteRepository;
    @Mock AppointmentRepository      appointmentRepository;

    @InjectMocks ConsultationNoteService noteService;

    private Appointment    appointment;
    private ConsultationNote note;

    @BeforeEach
    void setUp() {
        User patient = User.builder().id(1L).email("pat@test.com")
                .firstName("Alice").lastName("Patient").role(Role.ROLE_PATIENT).build();
        User docUser = User.builder().id(2L).email("doc@test.com")
                .firstName("Bob").lastName("Doctor").role(Role.ROLE_DOCTOR).build();
        Department dept = Department.builder().id(1L).name("Cardiology").build();
        Doctor doctor = Doctor.builder().id(10L).user(docUser).department(dept)
                .licenseNumber("LIC-001").build();

        appointment = Appointment.builder()
                .id(100L).patient(patient).doctor(doctor)
                .scheduledAt(LocalDateTime.now().plusDays(2))
                .status(AppointmentStatus.CONFIRMED).build();

        note = ConsultationNote.builder()
                .id(1L).appointment(appointment)
                .diagnosis("Type 2 Diabetes").treatmentPlan("Metformin 500mg daily")
                .prescriptions("Metformin 500mg").followUpDate(LocalDate.now().plusMonths(3))
                .build();
    }


    @Test
    @DisplayName("create — success builds note from appointment and request, saves with PHI fields")
    void create_success_persistsNoteWithAllFields() {
        when(noteRepository.findByAppointmentId(100L)).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(noteRepository.save(any())).thenReturn(note);
        ConsultationNoteRequest req = buildRequest("Hypertension", "Lisinopril 10mg", "Lisinopril", null);

        ConsultationNoteResponse response = noteService.create(100L, req);

        assertThat(response.getAppointmentId()).isEqualTo(100L);
        assertThat(response.getPatientName()).isEqualTo("Alice Patient");
        assertThat(response.getDoctorName()).isEqualTo("Bob Doctor");
        ArgumentCaptor<ConsultationNote> captor = ArgumentCaptor.forClass(ConsultationNote.class);
        verify(noteRepository).save(captor.capture());
        assertThat(captor.getValue().getDiagnosis()).isEqualTo("Hypertension");
        assertThat(captor.getValue().getTreatmentPlan()).isEqualTo("Lisinopril 10mg");
        assertThat(captor.getValue().getPrescriptions()).isEqualTo("Lisinopril");
        assertThat(captor.getValue().getAppointment()).isEqualTo(appointment);
    }

    @Test
    @DisplayName("create — note already exists for appointment throws 409 NOTE_EXISTS")
    void create_duplicateForSameAppointment_throwsConflict() {
        when(noteRepository.findByAppointmentId(100L)).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> noteService.create(100L, buildRequest("dx", "tx", null, null)))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> {
                    assertThat(((MediBookException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("NOTE_EXISTS");
                });
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("create — appointment not found throws NOT_FOUND without saving")
    void create_appointmentNotFound_throwsNotFound() {
        when(noteRepository.findByAppointmentId(999L)).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdWithDetails(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> noteService.create(999L, buildRequest("dx", "tx", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(noteRepository, never()).save(any());
    }

    @Test
    @DisplayName("create — followUpDate stored when provided")
    void create_withFollowUpDate_storesDate() {
        LocalDate followUp = LocalDate.now().plusMonths(1);
        when(noteRepository.findByAppointmentId(100L)).thenReturn(Optional.empty());
        when(appointmentRepository.findByIdWithDetails(100L)).thenReturn(Optional.of(appointment));
        when(noteRepository.save(any())).thenReturn(note);

        noteService.create(100L, buildRequest("dx", "tx", null, followUp));

        ArgumentCaptor<ConsultationNote> captor = ArgumentCaptor.forClass(ConsultationNote.class);
        verify(noteRepository).save(captor.capture());
        assertThat(captor.getValue().getFollowUpDate()).isEqualTo(followUp);
    }


    @Test
    @DisplayName("getByAppointment — existing note returns response")
    void getByAppointment_existing_returnsResponse() {
        when(noteRepository.findByAppointmentId(100L)).thenReturn(Optional.of(note));

        ConsultationNoteResponse response = noteService.getByAppointment(100L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getDiagnosis()).isEqualTo("Type 2 Diabetes");
    }

    @Test
    @DisplayName("getByAppointment — no note throws NOT_FOUND")
    void getByAppointment_notFound_throwsNotFound() {
        when(noteRepository.findByAppointmentId(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> noteService.getByAppointment(999L))
                .isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @DisplayName("getPatientHistory — returns list of notes ordered by createdAt DESC")
    void getPatientHistory_returnsAllPatientNotes() {
        when(noteRepository.findByPatientId(1L)).thenReturn(List.of(note));

        List<ConsultationNoteResponse> history = noteService.getPatientHistory(1L);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getDiagnosis()).isEqualTo("Type 2 Diabetes");
    }

    @Test
    @DisplayName("getPatientHistory — empty history returns empty list (no exception)")
    void getPatientHistory_noNotes_returnsEmptyList() {
        when(noteRepository.findByPatientId(1L)).thenReturn(List.of());

        assertThat(noteService.getPatientHistory(1L)).isEmpty();
    }


    @Test
    @DisplayName("update — success overwrites all mutable fields")
    void update_success_updatesAllFields() {
        when(noteRepository.findById(1L)).thenReturn(Optional.of(note));
        when(noteRepository.save(any())).thenReturn(note);
        LocalDate newFollowUp = LocalDate.now().plusWeeks(2);
        ConsultationNoteRequest req = buildRequest("Updated dx", "Updated tx", "New Rx", newFollowUp);

        noteService.update(1L, req);

        ArgumentCaptor<ConsultationNote> captor = ArgumentCaptor.forClass(ConsultationNote.class);
        verify(noteRepository).save(captor.capture());
        ConsultationNote saved = captor.getValue();
        assertThat(saved.getDiagnosis()).isEqualTo("Updated dx");
        assertThat(saved.getTreatmentPlan()).isEqualTo("Updated tx");
        assertThat(saved.getPrescriptions()).isEqualTo("New Rx");
        assertThat(saved.getFollowUpDate()).isEqualTo(newFollowUp);
    }

    @Test
    @DisplayName("update — note not found throws NOT_FOUND")
    void update_notFound_throwsNotFound() {
        when(noteRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> noteService.update(99L, buildRequest("dx", "tx", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(noteRepository, never()).save(any());
    }


    private ConsultationNoteRequest buildRequest(String diagnosis, String treatmentPlan,
                                                  String prescriptions, LocalDate followUp) {
        ConsultationNoteRequest req = new ConsultationNoteRequest();
        req.setDiagnosis(diagnosis);
        req.setTreatmentPlan(treatmentPlan);
        req.setPrescriptions(prescriptions);
        req.setFollowUpDate(followUp);
        return req;
    }
}
