package com.medibook.domain.telemedicine.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Twilio Video provider.
 * Set TWILIO_ACCOUNT_SID, TWILIO_API_KEY_SID, TWILIO_API_KEY_SECRET in environment.
 * Docs: https://www.twilio.com/docs/video/api
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.telemedicine.provider", havingValue = "twilio", matchIfMissing = false)
public class TwilioVideoProvider implements VideoRoomPort {

    private static final String BASE_URL = "https://video.twilio.com/v1";

    @Value("${app.telemedicine.twilio.account-sid:#{null}}") private String accountSid;
    @Value("${app.telemedicine.twilio.api-key-sid:#{null}}") private String apiKeySid;
    @Value("${app.telemedicine.twilio.api-key-secret:#{null}}") private String apiKeySecret;
    @Value("${app.telemedicine.twilio.video-room-type:group}") private String roomType;
    @Value("${app.telemedicine.twilio.video-status-callback-url:}") private String statusCallbackUrl;

    private final ObjectMapper objectMapper;

    public TwilioVideoProvider(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public VideoRoomProvider getProvider() { return VideoRoomProvider.TWILIO; }

    @Override
    public CreateRoomResult createRoom(CreateRoomRequest request) {
        if (!isConfigured()) return devStubRoom(request.appointmentId());

        try {
            RestClient client  = buildClient();
            String roomName    = "medibook-appt-" + request.appointmentId();

            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("UniqueName",      roomName);
            form.add("Type",            roomType);
            form.add("MaxParticipants", "2");
            if (statusCallbackUrl != null && !statusCallbackUrl.isBlank()) {
                form.add("StatusCallback", statusCallbackUrl);
                form.add("StatusCallbackMethod", "POST");
            }

            String response = client.post()
                    .uri("/Rooms")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            String sid    = node.path("sid").asText();
            String url    = "https://video.twilio.com/v1/Rooms/" + sid;
            log.info("Twilio room created: {} for appointment {}", sid, request.appointmentId());
            return new CreateRoomResult(sid, url);

        } catch (Exception ex) {
            log.error("Twilio createRoom failed: {}", ex.getMessage());
            throw new RuntimeException("Twilio video room creation failed", ex);
        }
    }

    @Override
    public JoinTokenResult generatePatientToken(String roomId, Long patientId, String patientName) {
        return generateToken(roomId, patientName, patientId);
    }

    @Override
    public JoinTokenResult generateDoctorToken(String roomId, Long doctorId, String doctorName) {
        return generateToken(roomId, doctorName, doctorId);
    }

    @Override
    public void closeRoom(String roomId) {
        if (!isConfigured()) return;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("Status", "completed");
            buildClient().post()
                    .uri("/Rooms/{sid}", roomId)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Twilio closeRoom failed: {}", ex.getMessage());
        }
    }

    private JoinTokenResult generateToken(String roomId, String identity, Long userId) {
        if (!isConfigured()) {
            return new JoinTokenResult("dev-twilio-token-" + userId,
                    "https://meet.medibook.io/room/" + roomId + "?user=" + userId);
        }
        // Legacy provider path. New chat-integrated calls use TwilioTokenService.
        String stub = "legacy-twilio-token-" + roomId + "-" + userId;
        return new JoinTokenResult(stub, "https://meet.medibook.io/room/" + roomId);
    }

    private RestClient buildClient() {
        String credentials = apiKeySid + ":" + apiKeySecret;
        String encoded     = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .build();
    }

    private boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
                && apiKeySid != null && !apiKeySid.isBlank()
                && apiKeySecret != null && !apiKeySecret.isBlank();
    }

    private CreateRoomResult devStubRoom(Long appointmentId) {
        String id = "medibook-appt-" + appointmentId;
        return new CreateRoomResult(id, "https://meet.medibook.io/room/" + id);
    }
}
