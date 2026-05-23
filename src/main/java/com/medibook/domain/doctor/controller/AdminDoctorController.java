package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.dto.AdminCreateDoctorRequest;
import com.medibook.domain.doctor.dto.DoctorAdminResponse;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.service.DoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/doctors")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@Tag(name = "Admin — Doctor Management", description = "Admin-only doctor lifecycle operations")
@SecurityRequirement(name = "bearerAuth")
public class AdminDoctorController {

    private final DoctorService    doctorService;
    private final DoctorRepository doctorRepository;
    private final com.medibook.domain.user.service.PasswordResetService passwordResetService;

    @PostMapping
    @Operation(summary = "Provision a new doctor account (creates user + doctor in one step)")
    public ResponseEntity<ApiResponse<DoctorAdminResponse>> create(@Valid @RequestBody AdminCreateDoctorRequest request) {
        DoctorResponse pub = doctorService.adminCreateDoctor(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(toAdmin(pub)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a doctor profile")
    public ResponseEntity<ApiResponse<DoctorAdminResponse>> activate(@PathVariable Long id) {
        DoctorResponse pub = doctorService.activate(id);
        return ResponseEntity.ok(ApiResponse.ok("Doctor activated", toAdmin(pub)));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a doctor profile (stops accepting new appointments)")
    public ResponseEntity<ApiResponse<DoctorAdminResponse>> deactivate(@PathVariable Long id) {
        DoctorResponse pub = doctorService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok("Doctor deactivated", toAdmin(pub)));
    }

    @PostMapping("/{id}/resend-invite")
    @Operation(summary = "Resend the welcome / set-up-password email to this doctor")
    public ResponseEntity<ApiResponse<Void>> resendInvite(@PathVariable Long id) {
        Doctor doctor = doctorRepository.findById(id)
                .orElseThrow(() -> new com.medibook.common.exception.ResourceNotFoundException("Doctor", "id", id));
        String token = passwordResetService.createInviteToken(doctor.getUser().getId());
        passwordResetService.sendInviteEmail(
                doctor.getUser().getEmail(),
                doctor.getUser().getFirstName() + " " + doctor.getUser().getLastName(),
                "Doctor",
                token);
        return ResponseEntity.ok(ApiResponse.noContent("Invite resent"));
    }

    private DoctorAdminResponse toAdmin(DoctorResponse pub) {
        Doctor doctor = doctorRepository.findById(pub.getId()).orElseThrow();
        return DoctorAdminResponse.fromPublic(pub, doctor.getAverageRating(), doctor.getReviewCount());
    }
}
