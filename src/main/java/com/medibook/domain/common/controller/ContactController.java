package com.medibook.domain.common.controller;

import com.medibook.common.response.ApiResponse;
import com.medibook.domain.common.dto.ContactRequest;
import com.medibook.domain.common.service.ContactService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/contact")
@RequiredArgsConstructor
@Tag(name = "Contact", description = "Public endpoint for contact requests")
public class ContactController {

    private final ContactService contactService;

    @PostMapping
    @Operation(summary = "Send a contact request")
    public ApiResponse<String> sendMessage(@Valid @RequestBody ContactRequest request) {
        contactService.sendMessage(request);
        return ApiResponse.ok("Message sent successfully");
    }
}
