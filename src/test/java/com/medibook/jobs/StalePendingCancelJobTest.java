package com.medibook.jobs;

import com.medibook.common.mail.TransactionalEmailService;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.user.entity.User;
import com.medibook.messaging.event.AppointmentEvent;
import com.medibook.messaging.producer.AppointmentEventProducer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("StalePendingCancelJob — Unit Tests")
class StalePendingCancelJobTest {

    @Mock AppointmentRepository       appointmentRepository;
    @Mock AppointmentEventProducer    eventProducer;
    @Mock TransactionalEmailService   emailService;
    @Mock MeterRegistry               meterRegistry;
    @Mock Counter                     counter;

    @InjectMocks StalePendingCancelJob job;

    @BeforeEach
    void wire() {
        ReflectionTestUtils.setField(job, "pendingTtlMinutes", 30);
        ReflectionTestUtils.setField(job, "reminderBeforeMinutes", 5);
        lenient().when(meterRegistry.counter(anyString(), any(String[].class))).thenReturn(counter);
        lenient().when(meterRegistry.counter(anyString())).thenReturn(counter);
    }

    @Test
    @DisplayName("cancelExpired flips stale PENDING to CANCELLED and publishes event")
    void cancelExpired_flipsAndPublishes() {
        Appointment a = staleAppointment();
        when(appointmentRepository.findStalePending(any(LocalDateTime.class)))
                .thenReturn(List.of(a));

        job.cancelExpired();

        // status flipped
        assertThat(a.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        verify(appointmentRepository).save(a);

        // CANCELLED event published with the right payload
        ArgumentCaptor<AppointmentEvent> evt = ArgumentCaptor.forClass(AppointmentEvent.class);
        verify(eventProducer).publishAppointmentEvent(evt.capture());
        assertThat(evt.getValue().getEventType()).isEqualTo("CANCELLED");
        assertThat(evt.getValue().getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
        assertThat(evt.getValue().getAppointmentId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("cancelExpired is a no-op when nothing is stale")
    void noStale_noWork() {
        when(appointmentRepository.findStalePending(any(LocalDateTime.class))).thenReturn(List.of());

        job.cancelExpired();

        verify(appointmentRepository, never()).save(any());
        verify(eventProducer, never()).publishAppointmentEvent(any());
    }

    @Test
    @DisplayName("run() catches exceptions so the scheduled tick is never lost")
    void runSwallowsExceptions() {
        when(appointmentRepository.findStalePending(any(LocalDateTime.class)))
                .thenThrow(new RuntimeException("db down"));
        job.run();

        verify(counter).increment();
    }

    private Appointment staleAppointment() {
        User patient = User.builder().id(1L).email("p@test.com").firstName("Jane").lastName("Doe").build();
        User docUser = User.builder().id(2L).email("d@test.com").firstName("Bob").lastName("MD").build();
        Doctor doc = Doctor.builder().id(7L).user(docUser).build();
        Appointment a = Appointment.builder()
                .id(42L)
                .patient(patient)
                .doctor(doc)
                .scheduledAt(LocalDateTime.now().plusHours(2))
                .status(AppointmentStatus.PENDING)
                .build();
        a.setCreatedAt(LocalDateTime.now().minusHours(1));   // older than 30-min TTL
        return a;
    }
}
