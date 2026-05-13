package com.medibook.domain.telemedicine.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatMessageRequest {

    @NotBlank(message = "Message cannot be blank")
    @Size(max = 5000)
    private String message;
}
