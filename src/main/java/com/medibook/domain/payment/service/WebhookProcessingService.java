package com.medibook.domain.payment.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medibook.domain.payment.entity.Payment;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookProcessingService {

    private final PaymentWebhookEventRepository webhookRepository;
    private final PaymentRepository             paymentRepository;
    private final PaymentProviderFactory        providerFactory;
    private final PaymentService                paymentService;
    private final ObjectMapper                  objectMapper;

    @Transactional
    public void processWebhook(String providerName, String payload, String signature, String idempotencyKey) {
        // Idempotency: skip if already processed
        if (webhookRepository.existsByIdempotencyKey(idempotencyKey)) {
            log.info("Duplicate webhook skipped: key={}", idempotencyKey);
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
            log.warn("Webhook signature verification FAILED for provider={}", providerName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid webhook signature");
        }

        PaymentWebhookEvent event = PaymentWebhookEvent.builder()
                .provider(providerName.toUpperCase())
                .payload(payload)
                .signature(signature)
                .idempotencyKey(idempotencyKey)
                .eventType(extractEventType(payload, provider))
                .build();

        try {
            handlePaymentWebhook(payload, provider);
            event.setProcessed(true);
            event.setProcessedAt(LocalDateTime.now());
        } catch (Exception ex) {
            log.error("Webhook processing failed: provider={} error={}", providerName, ex.getMessage());
            event.setFailureReason(ex.getMessage());
            event.setRetryCount(1);
        }

        webhookRepository.save(event);
    }

    private void handlePaymentWebhook(String payload,
                                      com.medibook.domain.payment.entity.PaymentProvider provider) {
        try {
            JsonNode node      = objectMapper.readTree(payload);
            String   reference = extractReference(node, provider);
            if (reference == null) {
                log.warn("Could not extract reference from {} webhook payload", provider);
                return;
            }

            paymentRepository.findByProviderRef(reference).ifPresentOrElse(
                    payment -> reconcilePayment(payment, node, provider),
                    () -> log.warn("No payment found for provider={} ref={}", provider, reference));

        } catch (Exception e) {
            log.error("Failed to parse {} webhook payload: {}", provider, e.getMessage());
            throw new RuntimeException("Webhook payload parsing failed", e);
        }
    }

    private void reconcilePayment(Payment payment, JsonNode node,
                                  com.medibook.domain.payment.entity.PaymentProvider provider) {
        BigDecimal amountPaid = extractAmountPaid(node, provider);
        if (amountPaid != null && amountPaid.compareTo(BigDecimal.ZERO) > 0
                && amountPaid.compareTo(payment.getAmount()) < 0) {
            log.warn("Webhook amount mismatch for payment [{}]: expected={} paid={} — rejecting",
                    payment.getId(), payment.getAmount(), amountPaid);
            return;
        }

        PaymentStatus newStatus = resolveStatusFromWebhook(node, provider);
        if (newStatus == null || payment.getStatus() == newStatus) {
            return; // no change needed
        }

        boolean wasAlreadySuccessful = payment.getStatus() == PaymentStatus.SUCCESSFUL;

        payment.setStatus(newStatus);
        paymentRepository.save(payment);
        log.info("Payment [{}] status → [{}] via {} webhook", payment.getId(), newStatus, provider);

        // Confirm invoice + publish event exactly once
        if (newStatus == PaymentStatus.SUCCESSFUL && !wasAlreadySuccessful) {
            paymentService.handleSuccessfulWebhookPayment(payment);
        }
    }

    private String extractReference(JsonNode node,
                                    com.medibook.domain.payment.entity.PaymentProvider provider) {
        return switch (provider) {
            case PAYSTACK    -> node.path("data").path("reference").asText(null);
            case FLUTTERWAVE -> node.path("data").path("tx_ref").asText(null);
            case STRIPE      -> node.path("data").path("object").path("id").asText(null);
            // Monnify sends the Monnify transactionReference (= our providerRef)
            case MONNIFY     -> node.path("eventData").path("transactionReference").asText(null);
        };
    }

    private PaymentStatus resolveStatusFromWebhook(JsonNode node,
                                                   com.medibook.domain.payment.entity.PaymentProvider provider) {
        String status = switch (provider) {
            case PAYSTACK    -> node.path("data").path("status").asText("");
            case FLUTTERWAVE -> node.path("data").path("status").asText("");
            case STRIPE      -> node.path("data").path("object").path("status").asText("");
            case MONNIFY     -> node.path("eventData").path("paymentStatus").asText("");
        };

        return switch (status.toUpperCase()) {
            case "SUCCESS", "SUCCESSFUL", "SUCCEEDED", "PAID", "OVERPAID" -> PaymentStatus.SUCCESSFUL;
            case "FAILED", "FAILURE", "EXPIRED"                            -> PaymentStatus.FAILED;
            case "CANCELLED"                                               -> PaymentStatus.CANCELLED;
            default -> null; // unknown / PENDING — no update
        };
    }

    /**
     * Extracts the amount paid from the webhook payload in the expected currency unit (not smallest unit).
     * Returns null if not extractable (no amount validation for that provider).
     */
    private BigDecimal extractAmountPaid(JsonNode node,
                                         com.medibook.domain.payment.entity.PaymentProvider provider) {
        return switch (provider) {
            case PAYSTACK -> {
                long kobo = node.path("data").path("amount").asLong(0);
                yield kobo > 0 ? BigDecimal.valueOf(kobo).divide(BigDecimal.valueOf(100)) : null;
            }
            case FLUTTERWAVE -> {
                double amt = node.path("data").path("amount").asDouble(0);
                yield amt > 0 ? BigDecimal.valueOf(amt) : null;
            }
            case STRIPE -> {
                long cents = node.path("data").path("object").path("amount_received").asLong(0);
                yield cents > 0 ? BigDecimal.valueOf(cents).divide(BigDecimal.valueOf(100)) : null;
            }
            // Monnify uses full currency units directly
            case MONNIFY -> {
                double amt = node.path("eventData").path("amountPaid").asDouble(0);
                yield amt > 0 ? BigDecimal.valueOf(amt) : null;
            }
        };
    }

    private String extractEventType(String payload,
                                    com.medibook.domain.payment.entity.PaymentProvider provider) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            return switch (provider) {
                case PAYSTACK, FLUTTERWAVE, STRIPE -> node.path("event").asText("UNKNOWN");
                case MONNIFY                       -> node.path("eventType").asText("UNKNOWN");
            };
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }
}
