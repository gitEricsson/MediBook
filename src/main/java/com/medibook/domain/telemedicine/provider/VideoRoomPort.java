package com.medibook.domain.telemedicine.provider;

/**
 * Strategy interface for video room creation.
 * Implementations: DailyCoVideoProvider, TwilioVideoProvider.
 * Active provider is selected by app.telemedicine.provider property.
 */
public interface VideoRoomPort {

    VideoRoomProvider getProvider();

    CreateRoomResult createRoom(CreateRoomRequest request);

    JoinTokenResult generatePatientToken(String roomId, Long patientId, String patientName);

    JoinTokenResult generateDoctorToken(String roomId, Long doctorId, String doctorName);

    void closeRoom(String roomId);

    enum VideoRoomProvider { DAILY_CO, TWILIO, STUB }

    record CreateRoomRequest(
            Long appointmentId,
            Long patientId,
            Long doctorId,
            int maxDurationMinutes
    ) {}

    record CreateRoomResult(
            String roomId,
            String roomUrl
    ) {}

    record JoinTokenResult(
            String token,
            String joinUrl
    ) {}
}
