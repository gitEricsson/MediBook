package com.medibook.domain.common.controller;

import com.medibook.common.response.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/lookups")
@Tag(name = "Lookups", description = "Reference data for UI components")
@SecurityRequirement(name = "bearerAuth")
public class LookupController {

    @GetMapping("/timezones")
    @Operation(summary = "Get available timezones for profile/scheduling")
    public ResponseEntity<ApiResponse<List<String>>> getTimezones() {
        List<String> zones = ZoneId.getAvailableZoneIds().stream()
                .sorted()
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.ok(zones));
    }

    @GetMapping("/languages")
    @Operation(summary = "Get supported languages for the platform")
    public ResponseEntity<ApiResponse<List<String>>> getLanguages() {
        List<String> languages = Arrays.asList(
                "English", "Spanish", "French", "German", "Chinese", 
                "Japanese", "Arabic", "Portuguese", "Russian", "Hindi"
        );
        return ResponseEntity.ok(ApiResponse.ok(languages));
    }
}
