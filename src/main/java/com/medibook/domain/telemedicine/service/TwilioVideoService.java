package com.medibook.domain.telemedicine.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.common.exception.MediBookException;
import com.medibook.domain.telemedicine.config.TwilioProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

@Slf4j
@Service
@RequiredArgsConstructor
public class TwilioVideoService {

    private static final String BASE_URL = "https://video.twilio.com/v1";

    private final TwilioProperties properties;
    private final ObjectMapper objectMapper;

    public RoomResult createRoom(String roomName) {
        if (!properties.isVideoConfigured()) {
            log.warn("Twilio Video is not configured; returning stub room {}", roomName);
            return new RoomResult("RM_STUB_" + roomName, roomName);
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("UniqueName", roomName);
            form.add("Type", properties.getVideoRoomType());
            form.add("MaxParticipants", "2");
            // Expire the Twilio room after the consultation window plus post-window grace.
            // Token TTL (900s) + 10-min pre + 10-min post window = at most ~2700s from creation.
            // We set a hard ceiling so rooms cannot stay open indefinitely after abandonment.
            form.add("MaxParticipantDuration", String.valueOf(properties.getRoomMaxDurationSeconds()));
            if (properties.getVideoStatusCallbackUrl() != null
                    && !properties.getVideoStatusCallbackUrl().isBlank()) {
                form.add("StatusCallback", properties.getVideoStatusCallbackUrl());
                form.add("StatusCallbackMethod", "POST");
            }

            String response = buildClient().post()
                    .uri("/Rooms")
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(String.class);

            JsonNode node = objectMapper.readTree(response);
            return new RoomResult(node.path("sid").asText(), node.path("unique_name").asText(roomName));
        } catch (Exception ex) {
            log.error("Twilio Video room creation failed: {}", ex.getMessage());
            throw new MediBookException("Could not create Twilio video room",
                    HttpStatus.BAD_GATEWAY, "TWILIO_ROOM_CREATE_FAILED");
        }
    }

    public void completeRoom(String roomSidOrName) {
        if (!properties.isVideoConfigured() || roomSidOrName == null || roomSidOrName.isBlank()) {
            return;
        }

        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("Status", "completed");
            buildClient().post()
                    .uri("/Rooms/{roomSidOrName}", roomSidOrName)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception ex) {
            log.warn("Twilio Video room completion failed for {}: {}", roomSidOrName, ex.getMessage());
        }
    }

    private RestClient buildClient() {
        String credentials = properties.getApiKeySid() + ":" + properties.getApiKeySecret();
        String encoded = Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
        return RestClient.builder()
                .baseUrl(BASE_URL)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Basic " + encoded)
                .build();
    }

    public record RoomResult(String roomSid, String roomName) {}
}
