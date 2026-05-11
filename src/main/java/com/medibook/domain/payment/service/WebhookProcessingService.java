package com.medibook.domain.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.entity.PaymentWebhookEvent;
import com.medibook.domain.payment.provider.PaymentProviderFactory;
import com.medibook.domain.payment.provider.PaymentProviderPort;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.payment.repository.PaymentWebhookEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessingService {

    private final PaymentWebhookEventRepository webhookRepository;
    private final PaymentRepository             paymentRepository;
    private final PaymentProviderFactory        providerFactory;
    private final ObjectMapper                  objectMapper;

    @Transactional
    public void processWebhook(String providerName, String payload, String signature, String idempotencyKey) {
        if (webhookRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Duplicate webhook received, skipping: key={}", idempotencyKey);
            return;
        }

        com.medibook.domain.payment.entity.PaymentProvider provider;
        try {
            provider = com.medibook.domain.payment.entity.PaymentProvider.valueOf(providerName.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown provider: " + providerName);
        }

        PaymentProviderPort port = providerFactory.get(provider);
        if (!port.verifyWebhookSignature(payload, signature)) {
            log.warn("Webhook signature verification failed for provider={}", providerName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }

        PaymentWebhookEvent event = PaymentWebhookEvent.builder()
                .provider(providerName.toUpperCase())
                .payload(payload)
                .signature(signature)
                .idempotencyKey(idempotencyKey)
                .eventType(extractEventType(payload))
                .build();

        try {
            handlePaymentWebhook(payload, provider);
            event.setProcessed(true);
            event.setProcessedAt(LocalDateTime.now());
        } catch (Exception ex) {
            log.error("Webhook processing failed for provider={}: {}", providerName, ex.getMessage());
            event.setFailureReason(ex.getMessage());
            event.setRetryCount(1);
        }

        webhookRepository.save(event);
    }

    private void handlePaymentWebhook(String payload, com.medibook.domain.payment.entity.PaymentProvider provider) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String reference = extractReference(node, provider);
            if (reference == null) return;

            paymentRepository.findByProviderRef(reference).ifPresent(payment -> {
                PaymentStatus newStatus = resolveStatusFromWebhook(node, provider);
                if (newStatus != null && payment.getStatus() != newStatus) {
                    payment.setStatus(newStatus);
                    paymentRepository.save(payment);
                    log.info("Payment [{}] status updated to [{}] via webhook", payment.getId(), newStatus);
                }
            });
        } catch (Exception e) {
            log.error("Failed to parse webhook payload", e);
        }
    }

    private String extractReference(JsonNode node, com.medibook.domain.payment.entity.PaymentProvider provider) {
        return switch (provider) {
            case PAYSTACK    -> node.path("data").path("reference").asText(null);
            case FLUTTERWAVE -> node.path("data").path("tx_ref").asText(null);
            case STRIPE      -> node.path("data").path("object").path("id").asText(null);
        };
    }

    private PaymentStatus resolveStatusFromWebhook(JsonNode node, com.medibook.domain.payment.entity.PaymentProvider provider) {
        String status = switch (provider) {
            case PAYSTACK    -> node.path("data").path("status").asText("");
            case FLUTTERWAVE -> node.path("data").path("status").asText("");
            case STRIPE      -> node.path("data").path("object").path("status").asText("");
        };
        return switch (status.toLowerCase()) {
            case "success", "successful", "succeeded" -> PaymentStatus.SUCCESSFUL;
            case "failed", "failure"                  -> PaymentStatus.FAILED;
            default -> null;
        };
    }

    private String extractEventType(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return node.path("event").asText("UNKNOWN");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
