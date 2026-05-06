package com.medibook.messaging.event;

import com.medibook.domain.appointment.entity.AppointmentStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka event published when an appointment is BOOKED, CONFIRMED, or CANCELLED.
 * Consumed by AppointmentEventConsumer (notifications) and AuditEventConsumer (Cassandra).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppointmentEvent {

    private String eventId;
    private String eventType;           // BOOKED | CONFIRMED | CANCELLED | COMPLETED

    private Long appointmentId;
    private Long patientId;
    private String patientEmail;
    private String patientName;

    private Long doctorId;
    private String doctorEmail;
    private String doctorName;
    private String departmentName;

    private LocalDateTime scheduledAt;
    private AppointmentStatus status;

    private LocalDateTime occurredAt;
}
