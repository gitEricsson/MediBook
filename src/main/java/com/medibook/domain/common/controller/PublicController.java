package com.medibook.domain.common.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Tag(name = "System", description = "Public system endpoints")
@RequiredArgsConstructor
public class PublicController {

    @Value("${app.version:1.0.0-SNAPSHOT}")
    private String appVersion;

    @GetMapping("/health")
    @Operation(summary = "Simple liveness check for the application shell")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/version")
    @Operation(summary = "Get application version and build hash")
    public ResponseEntity<Map<String, String>> version() {
        return ResponseEntity.ok(Map.of(
                "version", appVersion,
                "build", "dev-" + System.currentTimeMillis() // Mock build hash for now
        ));
    }
}
