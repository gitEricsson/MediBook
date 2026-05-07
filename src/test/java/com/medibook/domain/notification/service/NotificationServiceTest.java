package com.medibook.domain.notification.service;

import com.medibook.domain.notification.entity.Notification;
import com.medibook.domain.notification.repository.NotificationRepository;
import com.medibook.messaging.event.AppointmentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.cassandra.core.CassandraOperations;
import org.springframework.data.cassandra.core.query.Query;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("NotificationService — Unit Tests")
class NotificationServiceTest {

    @Mock NotificationRepository notificationRepository;
    @Mock CassandraOperations    cassandraOperations;

    @InjectMocks NotificationService notificationService;

    private AppointmentEvent event;

    @BeforeEach
    void setUp() {
        event = AppointmentEvent.builder()
                .appointmentId(100L)
                .patientId(1L).patientName("Alice Patient").patientEmail("alice@test.com")
                .doctorId(2L).doctorName("Dr. Bob Smith").doctorEmail("bob@test.com")
                .scheduledAt(LocalDateTime.now().plusDays(3))
                .build();
    }


    @Test
    @DisplayName("sendAppointmentBooked — saves one notification for patient and one for doctor")
    void sendAppointmentBooked_savesPatientAndDoctorNotifications() {
        notificationService.sendAppointmentBooked(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        List<Notification> saved = captor.getAllValues();
        assertThat(saved).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(saved).extracting(Notification::getType)
                .containsOnly("APPOINTMENT_BOOKED");
        assertThat(saved).extracting(Notification::getAppointmentId)
                .containsOnly(100L);
    }

    @Test
    @DisplayName("sendAppointmentBooked — both notifications have read=false")
    void sendAppointmentBooked_notificationsAreUnread() {
        notificationService.sendAppointmentBooked(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notification::isRead)
                .containsOnly(false);
    }


    @Test
    @DisplayName("sendAppointmentConfirmed — saves exactly one notification for the patient only")
    void sendAppointmentConfirmed_savesOnlyPatientNotification() {
        notificationService.sendAppointmentConfirmed(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(1)).save(captor.capture());
        Notification saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(1L);    // patient only
        assertThat(saved.getType()).isEqualTo("APPOINTMENT_CONFIRMED");
    }


    @Test
    @DisplayName("sendAppointmentCancelled — saves one notification for patient and one for doctor")
    void sendAppointmentCancelled_savesPatientAndDoctorNotifications() {
        notificationService.sendAppointmentCancelled(event);

        ArgumentCaptor<Notification> captor = ArgumentCaptor.forClass(Notification.class);
        verify(notificationRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Notification::getUserId)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(captor.getAllValues()).extracting(Notification::getType)
                .containsOnly("APPOINTMENT_CANCELLED");
    }


    @Test
    @DisplayName("getRecent — delegates to repository.findRecentByUserId and returns the list")
    void getRecent_delegatesToRepository() {
        Notification n = Notification.builder().userId(1L).build();
        when(notificationRepository.findRecentByUserId(1L)).thenReturn(List.of(n));

        List<Notification> result = notificationService.getRecent(1L);

        assertThat(result).hasSize(1);
        verify(notificationRepository).findRecentByUserId(1L);
    }

    @Test
    @DisplayName("getUnread — delegates to repository.findUnreadByUserId and returns the list")
    void getUnread_delegatesToRepository() {
        when(notificationRepository.findUnreadByUserId(1L)).thenReturn(List.of());

        List<Notification> result = notificationService.getUnread(1L);

        assertThat(result).isEmpty();
        verify(notificationRepository).findUnreadByUserId(1L);
    }


    @Test
    @DisplayName("purgeExpiredNotifications — calls CassandraOperations.delete with Notification class")
    void purgeExpiredNotifications_invokesCassandraDelete() {
        when(cassandraOperations.delete(any(Query.class), eq(Notification.class))).thenReturn(true);

        notificationService.purgeExpiredNotifications();

        verify(cassandraOperations).delete(any(Query.class), eq(Notification.class));
    }
}
