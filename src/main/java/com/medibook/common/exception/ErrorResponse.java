package com.medibook.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.List;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private boolean success;           // Always false for error responses
    private String message;            // Human-readable error message
    private String code;               // Machine-readable error code
    private Instant timestamp;         // ISO 8601 timestamp (UTC)
    private String correlationId;      // UUID for request correlation
    private String path;               // Request path
    private List<FieldError> fieldErrors; // Validation errors

    @Data
    @Builder
    public static class FieldError {
        private String field;
        private String message;
        private Object rejectedValue;
    }
}
