package com.medibook.domain.department.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.department.dto.DepartmentAdminResponse;
import com.medibook.domain.department.dto.DepartmentRequest;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1/admin/departments")
@RequiredArgsConstructor
@Tag(name = "Admin Departments", description = "Admin-only department management endpoints")
@PreAuthorize("hasAnyRole('ADMIN', 'SUPER_ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminDepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    @Operation(summary = "Get department stats with pagination and filtering")
    public ResponseEntity<ApiResponse<Page<DepartmentAdminResponse>>> getStats(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean status,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(ApiResponse.ok(departmentService.getAdminStats(q, status, pageable)));
    }

    @PostMapping
    @Operation(summary = "Create a new department")
    public ResponseEntity<ApiResponse<DepartmentResponse>> create(@Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.created(departmentService.create(request)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get department by ID")
    public ResponseEntity<ApiResponse<DepartmentResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(departmentService.getById(id)));
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Update department details")
    public ResponseEntity<ApiResponse<DepartmentResponse>> update(
            @PathVariable Long id, @Valid @RequestBody DepartmentRequest request) {
        return ResponseEntity.ok(ApiResponse.ok(departmentService.update(id, request)));
    }

    @PostMapping("/{id}/deactivate")
    @Operation(summary = "Soft-deactivate a department")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        departmentService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.noContent("Department deactivated"));
    }

    @PostMapping("/{id}/reactivate")
    @Operation(summary = "Reactivate a department")
    public ResponseEntity<ApiResponse<Void>> reactivate(@PathVariable Long id) {
        departmentService.reactivate(id);
        return ResponseEntity.ok(ApiResponse.noContent("Department reactivated"));
    }

    @GetMapping(value = "/export.csv", produces = "text/csv")
    @Operation(summary = "Export departments to CSV")
    public ResponseEntity<byte[]> exportCsv() {
        List<DepartmentAdminResponse> stats = departmentService.getAllAdminStats();
        
        StringBuilder csv = new StringBuilder();
        csv.append("ID,Name,Code,Doctors Count,Appt Count 90d,Status\n");
        
        for (DepartmentAdminResponse d : stats) {
            csv.append(String.format("%d,\"%s\",\"%s\",%d,%d,%s\n",
                    d.getId(), 
                    d.getName().replace("\"", "\"\""), 
                    d.getCode(), 
                    d.getDoctorsCount(), 
                    d.getApptCount90d(), 
                    d.isStatus() ? "ACTIVE" : "INACTIVE"));
        }

        byte[] csvBytes = csv.toString().getBytes(StandardCharsets.UTF_8);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "departments.csv");

        return new ResponseEntity<>(csvBytes, headers, HttpStatus.OK);
    }
}
