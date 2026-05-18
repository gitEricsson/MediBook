package com.medibook.domain.department.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/departments")
@RequiredArgsConstructor
@Tag(name = "Departments", description = "Hospital department read-only endpoints")
@SecurityRequirement(name = "bearerAuth")
public class DepartmentController {

    private final DepartmentService departmentService;

    @GetMapping
    @Operation(summary = "List all active departments (any authenticated user)")
    public ResponseEntity<ApiResponse<List<DepartmentResponse>>> getAllActive() {
        return ResponseEntity.ok(ApiResponse.ok(departmentService.getAllActive()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get department by ID (any authenticated user)")
    public ResponseEntity<ApiResponse<DepartmentResponse>> getById(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.ok(departmentService.getById(id)));
    }
}
