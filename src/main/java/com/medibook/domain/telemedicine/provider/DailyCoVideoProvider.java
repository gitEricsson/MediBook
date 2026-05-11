package com.medibook.domain.telemedicine.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Daily.co video room provider.
 * Set DAILY_CO_API_KEY in environment for production use.
 * Docs: https://docs.daily.co/reference/rest-api
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.telemedicine.provider", havingValue = "daily-co", matchIfMissing = false)
public class DailyCoVideoProvider implements VideoRoomPort {

    private static final String BASE_URL = "https://api.daily.co/v1";

    @Value("${app.telemedicine.daily-co.api-key:#{null}}")
    private String apiKey;

    private final ObjectMapper objectMapper;

    public DailyCoVideoProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public VideoRoomProvider getProvider() { return VideoRoomProvider.DAILY_CO; }

    @Override
    @CircuitBreaker(name = "videoRoomProvider", fallbackMethod = "createRoomFallback")
    public CreateRoomResult createRoom(CreateRoomRequest request) {
        if (!isConfigured()) return devStubRoom(request.appointmentId());

        try {
            RestClient client = buildClient();
            long expiry       = Instant.now().plusSeconds((long) request.maxDurationMinutes() * 60 + 300).getEpochSecond();

            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "medibook-appt-" + request.appointmentId(),
                    "privacy", "private",
                    "properties", Map.of(
                            "exp",       expiry,
                            "max_participants", 2,
                            "enable_chat", true,
                            "enable_screenshare", false,
                            "start_video_off", false,
                            "start_audio_off", false
                    )
            ));

            String response = client.post()
                    .uri("/rooms")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            String roomId = node.path("id").asText();
            String url    = node.path("url").asText();
            log.info("Daily.co room created: {} for appointment {}", roomId, request.appointmentId());
            return new CreateRoomResult(roomId, url);

        } catch (Exception ex) {
            log.error("Daily.co createRoom failed: {}", ex.getMessage());
            throw new RuntimeException("Video room creation failed", ex);
        }
    }

    @Override
    @CircuitBreaker(name = "videoRoomProvider", fallbackMethod = "generateTokenFallback")
    public JoinTokenResult generatePatientToken(String roomId, Long patientId, String patientName) {
        return generateToken(roomId, patientId, patientName, false);
    }

    @Override
    @CircuitBreaker(name = "videoRoomProvider", fallbackMethod = "generateTokenFallback")
    public JoinTokenResult generateDoctorToken(String roomId, Long doctorId, String doctorName) {
        return generateToken(roomId, doctorId, doctorName, true);
    }

    @Override
    public void closeRoom(String roomId) {
        if (!isConfigured()) return;
        try {
            buildClient().delete().uri("/rooms/{name}", roomId).retrieve().toBodilessEntity();
            log.info("Daily.co room closed: {}", roomId);
        } catch (Exception ex) {
            log.warn("Daily.co closeRoom failed for {}: {}", roomId, ex.getMessage());
        }
    }

    private JoinTokenResult generateToken(String roomId, Long userId, String userName, boolean isDoctor) {
        if (!isConfigured()) {
            return new JoinTokenResult("dev-token-" + userId, devRoomUrl(roomId) + "?t=dev-" + userId);
        }

        try {
            RestClient client = buildClient();
            long expiry       = Instant.now().plusSeconds(7200).getEpochSecond();
            String body       = objectMapper.writeValueAsString(Map.of(
                    "properties", Map.of(
                            "room_name",     roomId,
                            "user_name",     userName,
                            "user_id",       String.valueOf(userId),
                            "is_owner",      isDoctor,
                            "exp",           expiry,
                            "enable_recording", isDoctor
                    )
            ));

            String response = client.post()
                    .uri("/meeting-tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            String token  = node.path("token").asText();
            String joinUrl = "https://medibook.daily.co/" + roomId + "?t=" + token;
            return new JoinTokenResult(token, joinUrl);

        } catch (Exception ex) {
            log.error("Daily.co generateToken failed: {}", ex.getMessage());
            throw new RuntimeException("Video token generation failed", ex);
        }
    }

    // Fallbacks
    CreateRoomResult createRoomFallback(CreateRoomRequest request, Exception ex) {
        log.warn("Daily.co circuit open — stub room for appointment {}", request.appointmentId());
        return devStubRoom(request.appointmentId());
    }

    JoinTokenResult generateTokenFallback(String roomId, Long userId, String name, Exception ex) {
        return new JoinTokenResult("cb-token-" + userId, devRoomUrl(roomId) + "?t=cb-" + userId);
    }

    private RestClient buildClient() {
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    private CreateRoomResult devStubRoom(Long appointmentId) {
        String id = "medibook-appt-" + appointmentId;
        return new CreateRoomResult(id, devRoomUrl(id));
    }

    private String devRoomUrl(String roomId) {
        return "https://medibook.daily.co/" + roomId;
    }
}
