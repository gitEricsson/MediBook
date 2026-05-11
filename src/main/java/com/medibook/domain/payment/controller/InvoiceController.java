package com.medibook.domain.payment.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.payment.dto.InvoiceResponse;
import com.medibook.domain.payment.service.PaymentService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
@Tag(name = "Invoices", description = "Invoice retrieval operations")
public class InvoiceController {

    private final PaymentService paymentService;

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('PATIENT', 'ADMIN')")
    @Operation(summary = "Get invoice by ID")
    public ApiResponse<InvoiceResponse> getInvoice(
            @PathVariable Long id,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.ok(paymentService.getInvoice(id, principal));
    }

    @GetMapping("/my")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(summary = "Get my invoices")
    public ApiResponse<Page<InvoiceResponse>> getMyInvoices(
            @CurrentUser UserPrincipal principal,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(paymentService.getInvoicesForPatient(principal.getId(), pageable));
    }
}
