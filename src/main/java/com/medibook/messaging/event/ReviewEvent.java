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
public class ReviewEvent {
    private String eventId;
    private String eventType; // SUBMITTED | APPROVED | REJECTED
    
    private Long reviewId;
    private Long appointmentId;
    private Long patientId;
    private Long doctorId;
    private String doctorName;
    private Integer rating;
    
    private LocalDateTime occurredAt;
}
