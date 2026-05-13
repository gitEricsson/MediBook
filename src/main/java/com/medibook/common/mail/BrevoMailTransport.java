package com.medibook.common.mail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class BrevoMailTransport {

    private final MailProperties mailProperties;
    private final ObjectMapper objectMapper;

    public boolean isAvailable() {
        return mailProperties.getBrevo().isEnabled()
                && StringUtils.hasText(mailProperties.getBrevo().getApiKey())
                && StringUtils.hasText(mailProperties.getFromAddress());
    }

    public void send(OutboundEmail email) throws Exception {
        String response = RestClient.builder()
                .baseUrl(mailProperties.getBrevo().getBaseUrl())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("api-key", mailProperties.getBrevo().getApiKey())
                .build()
                .post()
                .uri("/v3/smtp/email")
                .body(Map.of(
                        "sender", Map.of(
                                "email", mailProperties.getFromAddress(),
                                "name", mailProperties.getFromName()
                        ),
                        "to", List.of(Map.of("email", email.toEmail())),
                        "subject", email.subject(),
                        "htmlContent", email.htmlBody()
                ))
                .retrieve()
                .body(String.class);

        JsonNode responseBody = objectMapper.readTree(response);
        log.info("Brevo email sent to {} messageId={}",
                email.toEmail(),
                responseBody.path("messageId").asText("unknown"));
    }
}
