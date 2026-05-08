package com.medibook.domain.doctor.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.service.DepartmentService;
import com.medibook.domain.doctor.repository.DoctorRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Metadata", description = "Public metadata for filters")
public class MetadataController {

    private final DepartmentService departmentService;
    private final DoctorRepository doctorRepository;



    @GetMapping("/specialisations")
    @Operation(summary = "Get distinct specialisations from active doctors")
    public ResponseEntity<ApiResponse<List<String>>> getSpecialisations() {
        List<String> specs = doctorRepository.findAll().stream()
                .filter(d -> d.isActive())
                .map(d -> d.getSpecialization())
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(specs));
    }
}
