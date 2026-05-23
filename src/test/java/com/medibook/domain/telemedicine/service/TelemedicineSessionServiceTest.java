package com.medibook.domain.telemedicine.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.telemedicine.dto.TelemedicineSessionResponse;
import com.medibook.domain.telemedicine.entity.TelemedicineSession;
import com.medibook.domain.telemedicine.entity.TelemedicineSessionStatus;
import com.medibook.domain.telemedicine.provider.VideoRoomPort;
import com.medibook.domain.telemedicine.repository.CassandraChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineChatMessageRepository;
import com.medibook.domain.telemedicine.repository.TelemedicineSessionRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.messaging.producer.OutboxEventProducer;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemedicineSessionServiceTest {

    @Mock TelemedicineSessionRepository sessionRepository;
    @Mock TelemedicineChatMessageRepository chatMessageRepository;
    @Mock CassandraChatMessageRepository cassandraChatRepo;
    @Mock AppointmentRepository         appointmentRepository;
    @Mock UserRepository                userRepository;
    @Mock VideoRoomPort                 videoRoomPort;
    @Mock OutboxEventProducer           eventProducer;

    @InjectMocks TelemedicineSessionService sessionService;

    private User patient;
    private User doctorUser;
    private Doctor doctor;
    private Appointment appointment;
    private UserPrincipal patientPrincipal;
    private UserPrincipal doctorPrincipal;

    @BeforeEach
    void setUp() {
        patient    = User.builder().id(1L).email("p@test.com").firstName("Jane").lastName("Doe").role(Role.ROLE_PATIENT).build();
        doctorUser = User.builder().id(2L).email("doc@test.com").firstName("Dr").lastName("Smith").role(Role.ROLE_DOCTOR).build();

        Department dept = Department.builder().id(1L).name("Cardiology").build();
        doctor = Doctor.builder().id(1L).user(doctorUser).department(dept)
                .licenseNumber("L001").build();

        appointment = Appointment.builder()
                .id(1L).patient(patient).doctor(doctor).department(dept)
                .scheduledAt(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusMinutes(30))
                .durationMins(30).status(AppointmentStatus.CONFIRMED)
                .type(AppointmentType.TELEMEDICINE).confirmationCode("MB-TM01").build();

        patientPrincipal = UserPrincipal.fromUser(patient);
        doctorPrincipal  = UserPrincipal.fromUser(doctorUser);
    }

    @Test
    void createSession_success() {
        when(appointmentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(appointment));
        when(sessionRepository.findByAppointmentId(1L)).thenReturn(Optional.empty());
        when(videoRoomPort.createRoom(any())).thenReturn(
                new VideoRoomPort.CreateRoomResult("stub-room-1", "https://meet.medibook.io/room/stub-room-1"));
        when(videoRoomPort.generatePatientToken(any(), any(), any())).thenReturn(
                new VideoRoomPort.JoinTokenResult("ptok", "https://meet.medibook.io/room/stub-room-1?role=patient"));
        when(videoRoomPort.generateDoctorToken(any(), any(), any())).thenReturn(
                new VideoRoomPort.JoinTokenResult("dtok", "https://meet.medibook.io/room/stub-room-1?role=doctor"));

        TelemedicineSession saved = TelemedicineSession.builder()
                .id(1L).appointment(appointment).status(TelemedicineSessionStatus.SCHEDULED)
                .roomId("stub-room-1").patientConsent(true)
                .joinUrlPatient("https://meet.medibook.io/room/stub-room-1?role=patient")
                .joinUrlDoctor("https://meet.medibook.io/room/stub-room-1?role=doctor")
                .build();
        when(sessionRepository.save(any())).thenReturn(saved);

        TelemedicineSessionResponse response = sessionService.createSession(1L, true, patientPrincipal);

        assertThat(response.getStatus()).isEqualTo(TelemedicineSessionStatus.SCHEDULED);
        assertThat(response.isPatientConsent()).isTrue();
        assertThat(response.getRoomId()).isEqualTo("stub-room-1");
    }

    @Test
    void createSession_notTelemedicine_throwsException() {
        appointment.setType(AppointmentType.IN_PERSON);
        when(appointmentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(appointment));

        assertThatThrownBy(() -> sessionService.createSession(1L, true, patientPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("not a telemedicine");
    }


    @Test
    void createSession_duplicateSession_throwsConflict() {
        when(appointmentRepository.findByIdWithDetails(1L)).thenReturn(Optional.of(appointment));
        when(sessionRepository.findByAppointmentId(1L)).thenReturn(
                Optional.of(TelemedicineSession.builder().id(99L).appointment(appointment).build()));

        assertThatThrownBy(() -> sessionService.createSession(1L, true, patientPrincipal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("already exists");
    }
}
