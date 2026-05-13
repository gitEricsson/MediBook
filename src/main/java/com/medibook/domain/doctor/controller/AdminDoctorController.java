package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.dto.AdminCreateDoctorRequest;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
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

    private final DoctorService doctorService;

    @PostMapping
    @Operation(summary = "Provision a new doctor account (creates user + doctor in one step)")
    public ResponseEntity<ApiResponse<DoctorResponse>> create(@Valid @RequestBody AdminCreateDoctorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(doctorService.adminCreateDoctor(request)));
    }

    @PostMapping("/{id}/activate")
    @Operation(summary = "Activate a doctor profile")
    public ResponseEntity<ApiResponse<DoctorResponse>> activate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Doctor activated", doctorService.activate(id)));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Deactivate a doctor profile (stops accepting new appointments)")
    public ResponseEntity<ApiResponse<DoctorResponse>> deactivate(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok("Doctor deactivated", doctorService.deactivate(id)));
    }
}
