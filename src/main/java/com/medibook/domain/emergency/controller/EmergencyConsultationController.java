package com.medibook.domain.emergency.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.appointment.dto.EmergencyFeeEstimateResponse;
import com.medibook.domain.appointment.entity.ConsultationMedium;
import com.medibook.domain.appointment.service.AppointmentPricingService;
import com.medibook.domain.emergency.dto.EmergencyConsultationRequest;
import com.medibook.domain.emergency.dto.EmergencyConsultationResponse;
import com.medibook.domain.emergency.service.EmergencyConsultationService;
import com.medibook.security.CurrentUser;
import com.medibook.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/emergency")
@RequiredArgsConstructor
@Tag(name = "Emergency", description = "Emergency consultation operations")
public class EmergencyConsultationController {

    private final EmergencyConsultationService emergencyService;
    private final AppointmentPricingService pricingService;

    @PostMapping("/request")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Request an emergency consultation",
        description = "Immediately assigns an available doctor. Consultation type defaults to EMERGENCY. "
                + "Payment settlement occurs post-consultation. "
                + "Blocked if patient has unresolved emergency debt (unless criticalOverride=true)."
    )
    public ApiResponse<EmergencyConsultationResponse> requestEmergency(
            @Valid @RequestBody EmergencyConsultationRequest request,
            @CurrentUser UserPrincipal principal) {
        return ApiResponse.created(emergencyService.requestEmergency(request, principal));
    }

    @GetMapping("/fee-estimate")
    @PreAuthorize("hasRole('PATIENT')")
    @Operation(
        summary = "Preview the emergency consultation fee",
        description = "Returns the department base + emergency surcharge + medium surcharge breakdown, "
                + "plus the senior-consultant premium that would apply if the auto-assigned doctor "
                + "turns out to be a senior consultant. Use on the emergency screen so the patient "
                + "sees what they'll owe before submitting."
    )
    public ApiResponse<EmergencyFeeEstimateResponse> previewFee(
            @RequestParam(required = false) Long departmentId,
            @RequestParam(defaultValue = "VIDEO") ConsultationMedium medium) {
        return ApiResponse.ok(pricingService.estimateEmergencyFee(departmentId, medium));
    }
}
