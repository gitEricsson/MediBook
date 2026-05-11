package com.medibook.domain.payment.controller;

import com.medibook.domain.payment.service.WebhookProcessingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Generic webhook receiver for all payment providers.
 *
 * Supported providers and their signature headers:
 *   paystack    → X-Paystack-Signature   (HMAC-SHA512)
 *   flutterwave → verif-hash             (provider header)
 *   stripe      → Stripe-Signature       (v1 scheme)
 *   monnify     → monnify-signature      (SHA-512 of secretKey + body)
 *
 * URL: POST /api/v1/payments/webhooks/{provider}
 * This path is in PUBLIC_ENDPOINTS in SecurityConfig — no JWT required.
 * Signature validation inside WebhookProcessingService is the security boundary.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/payments/webhooks")
@RequiredArgsConstructor
@Tag(name = "Payment Webhooks", description = "Provider webhook endpoints")
public class WebhookController {

    private final WebhookProcessingService webhookService;

    @PostMapping("/{provider}")
    @Operation(summary = "Receive webhook from payment provider")
    public ResponseEntity<Map<String, String>> handleWebhook(
            @PathVariable String provider,
            @RequestBody String payload,
            @RequestHeader(value = "X-Paystack-Signature",  required = false) String paystackSig,
            @RequestHeader(value = "verif-hash",            required = false) String flutterwaveSig,
            @RequestHeader(value = "Stripe-Signature",      required = false) String stripeSig,
            @RequestHeader(value = "monnify-signature",     required = false) String monnifySig,
            @RequestHeader(value = "Idempotency-Key",       required = false) String idempotencyKey) {

        String signature = resolveSignature(provider, paystackSig, flutterwaveSig, stripeSig, monnifySig);
        String key       = idempotencyKey != null ? idempotencyKey : UUID.randomUUID().toString();

        log.info("Webhook received: provider={} idempotencyKey={}", provider, key);
        webhookService.processWebhook(provider, payload, signature, key);

        return ResponseEntity.ok(Map.of("status", "received"));
    }

    private String resolveSignature(String provider,
                                    String paystack,
                                    String flutterwave,
                                    String stripe,
                                    String monnify) {
        return switch (provider.toLowerCase()) {
            case "paystack"    -> paystack    != null ? paystack    : "";
            case "flutterwave" -> flutterwave != null ? flutterwave : "";
            case "stripe"      -> stripe      != null ? stripe      : "";
            case "monnify"     -> monnify     != null ? monnify     : "";
            default            -> "";
        };
    }
}
