package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.dto.DoctorRequest;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.service.DoctorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/doctors")
@RequiredArgsConstructor
@Tag(name = "Doctors", description = "Doctor profile and department assignment")
@SecurityRequirement(name = "bearerAuth")
public class DoctorController {

    private final DoctorService doctorService;

    @GetMapping
    @Operation(summary = "List all doctors with pagination")
    public ResponseEntity<ApiResponse<Page<DoctorResponse>>> getAll(
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(doctorService.getAll(pageable)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get doctor by ID")
    public ResponseEntity<ApiResponse<DoctorResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(doctorService.getById(id)));
    }

    @GetMapping("/department/{departmentId}")
    @Operation(summary = "List doctors by department")
    public ResponseEntity<ApiResponse<Page<DoctorResponse>>> getByDepartment(
            @PathVariable Long departmentId, @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(doctorService.getByDepartment(departmentId, pageable)));
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Register a new doctor (Admin only)")
    public ResponseEntity<ApiResponse<DoctorResponse>> register(@Valid @RequestBody DoctorRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(doctorService.register(request)));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'DOCTOR')")
    @Operation(summary = "Update doctor profile (Admin or own Doctor)")
    public ResponseEntity<ApiResponse<DoctorResponse>> update(
            @PathVariable Long id, @Valid @RequestBody DoctorRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(doctorService.update(id, request)));
    }
}
