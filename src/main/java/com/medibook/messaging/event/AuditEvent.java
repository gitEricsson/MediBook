package com.medibook.messaging.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka audit event — written to Cassandra audit_log by AuditEventConsumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent {

    private String eventId;
    private String action;          // e.g. APPOINTMENT_BOOKED, USER_LOGIN, DOCTOR_UPDATED
    private Long actorId;           // who performed the action
    private String actorEmail;
    private String resourceType;    // e.g. Appointment, User, Doctor
    private String resourceId;
    private String detail;          // JSON or human-readable description
    private LocalDateTime occurredAt;
}
