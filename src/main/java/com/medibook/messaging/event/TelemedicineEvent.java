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
public class TelemedicineEvent {
    private String eventId;
    private String eventType; // READY | STARTED | JOINED | LEFT | ENDED
    
    private Long sessionId;
    private Long appointmentId;
    private Long patientId;
    private Long doctorId;
    
    private LocalDateTime occurredAt;
}
