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
 * Set TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN, TWILIO_API_KEY, TWILIO_API_SECRET in environment.
 * Docs: https://www.twilio.com/docs/video/api
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.telemedicine.provider", havingValue = "twilio", matchIfMissing = false)
public class TwilioVideoProvider implements VideoRoomPort {

    private static final String BASE_URL = "https://video.twilio.com/v1";

    @Value("${app.telemedicine.twilio.account-sid:#{null}}") private String accountSid;
    @Value("${app.telemedicine.twilio.auth-token:#{null}}")  private String authToken;
    @Value("${app.telemedicine.twilio.api-key:#{null}}")     private String apiKey;
    @Value("${app.telemedicine.twilio.api-secret:#{null}}")  private String apiSecret;

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
            form.add("Type",            "go");          // Twilio Go = up to 2 participants
            form.add("MaxParticipants", "2");

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
        // Production: use the Twilio JWT library to generate an Access Token with VideoGrant.
        // Returning a formatted stub until the JWT library (com.twilio.sdk:twilio) is added.
        // The JWT structure: header.payload.signature using apiKey/apiSecret and accountSid.
        log.warn("Twilio JWT generation requires twilio-java SDK — returning formatted stub token");
        String stub = "TWILIO_JWT_STUB_" + apiKey + "_" + roomId + "_" + userId;
        return new JoinTokenResult(stub, "https://meet.medibook.io/room/" + roomId);
    }

    private RestClient buildClient() {
        String credentials = accountSid + ":" + authToken;
        String encoded     = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .build();
    }

    private boolean isConfigured() {
        return accountSid != null && !accountSid.isBlank()
                && authToken != null && !authToken.isBlank();
    }

    private CreateRoomResult devStubRoom(Long appointmentId) {
        String id = "medibook-appt-" + appointmentId;
        return new CreateRoomResult(id, "https://meet.medibook.io/room/" + id);
    }
}
