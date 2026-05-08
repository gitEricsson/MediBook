package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.doctor.dto.AvailabilityGridResponse;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.service.DoctorSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/v1/doctors")
@RequiredArgsConstructor
@Tag(name = "Doctors", description = "Doctor search and availability")
@SecurityRequirement(name = "bearerAuth")
public class DoctorSearchController {

    private final DoctorSearchService doctorSearchService;

    @GetMapping("/search")
    @Operation(summary = "Search doctors with filters")
    public ResponseEntity<ApiResponse<Page<DoctorResponse>>> searchDoctors(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) List<Long> departmentId,
            @RequestParam(required = false) List<String> specialisation,
            @RequestParam(required = false) String availability,
            @RequestParam(required = false) String visitType,
            @RequestParam(required = false) Boolean acceptingNew,
            Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(doctorSearchService.searchDoctors(
                q, departmentId, specialisation, availability, visitType, acceptingNew, pageable)));
    }

    @GetMapping("/{id}/availability")
    @Operation(summary = "Get doctor availability slot grid")
    public ResponseEntity<ApiResponse<AvailabilityGridResponse>> getAvailability(
            @PathVariable Long id,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String tz) {
        return ResponseEntity.ok(ApiResponse.ok(doctorSearchService.getAvailability(id, from, to)));
    }
}
