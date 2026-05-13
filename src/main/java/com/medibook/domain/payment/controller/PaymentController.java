package com.medibook.domain.payment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.payment.dto.InitiatePaymentRequest;
import com.medibook.domain.payment.dto.InvoiceResponse;
import com.medibook.domain.payment.dto.PaymentResponse;
import com.medibook.domain.payment.service.PaymentService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Appointment payment operations")
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Initiate a payment for an appointment")
    public ApiResponse<PaymentResponse> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(paymentService.initiatePayment(request, principal));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize("hasAnyRole('PATIENT', 'ADMIN')")
    @Operation(summary = "Verify payment status from provider")
    public ApiResponse<PaymentResponse> verifyPayment(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(paymentService.verifyPayment(id, principal));
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAnyRole('PATIENT', 'ADMIN')")
    @Operation(summary = "Request a refund for a successful payment")
    public ApiResponse<PaymentResponse> refundPayment(
            @PathVariable Long id,
            @RequestParam(required = false) BigDecimal amount,
            @RequestParam(required = false) String reason,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(paymentService.refundPayment(id, amount, reason, principal));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get my payment history")
    public ApiResponse<Page<PaymentResponse>> getMyPayments(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(paymentService.getPaymentsForPatient(principal.getId(), pageable));
    }
}
