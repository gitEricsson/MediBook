package com.medibook.domain.telemedicine.provider;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Fallback stub video provider — used when no real provider is configured.
 * Active by default (matchIfMissing = true) unless daily-co or twilio is set.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.telemedicine.provider", havingValue = "stub", matchIfMissing = true)
public class StubVideoProvider implements VideoRoomPort {

    @Override
    public VideoRoomProvider getProvider() { return VideoRoomProvider.STUB; }

    @Override
    public CreateRoomResult createRoom(CreateRoomRequest request) {
        String roomId = "stub-room-appt-" + request.appointmentId();
        String url    = "https://meet.medibook.io/room/" + roomId;
        log.info("StubVideoProvider: created room {} for appointment {}", roomId, request.appointmentId());
        return new CreateRoomResult(roomId, url);
    }

    @Override
    public JoinTokenResult generatePatientToken(String roomId, Long patientId, String patientName) {
        String token  = "stub-patient-token-" + patientId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String joinUrl = "https://meet.medibook.io/room/" + roomId + "?role=patient&t=" + token;
        return new JoinTokenResult(token, joinUrl);
    }

    @Override
    public JoinTokenResult generateDoctorToken(String roomId, Long doctorId, String doctorName) {
        String token   = "stub-doctor-token-" + doctorId + "-" + UUID.randomUUID().toString().substring(0, 8);
        String joinUrl = "https://meet.medibook.io/room/" + roomId + "?role=doctor&t=" + token;
        return new JoinTokenResult(token, joinUrl);
    }

    @Override
    public void closeRoom(String roomId) {
        log.info("StubVideoProvider: closed room {}", roomId);
    }
}
