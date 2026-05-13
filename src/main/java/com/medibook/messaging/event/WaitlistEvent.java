package com.medibook.messaging.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistEvent {
    private String eventId;
    private String eventType; // JOINED | LEFT | PROMOTED | EXPIRED
    
    private Long waitlistId;
    private Long patientId;
    private Long doctorId;
    private Long appointmentId; // For PROMOTED
    
    private String doctorName;
    private LocalDateTime scheduledAt;
    
    private LocalDateTime occurredAt;
}
