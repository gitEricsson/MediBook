package com.medibook.domain.user.dto;

import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class UserResponse {

    private Long id;
    private String email;
    private String firstName;
    private String lastName;
    private String fullName;
    private String phone;
    private Role role;
    private boolean enabled;
    private boolean active;
    private boolean twoFactorEnabled;
    private boolean emailNotifications;
    private boolean smsNotifications;
    private String locale;
    private LocalDateTime createdAt;

    public static UserResponse fromUser(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .role(user.getRole())
                .enabled(user.isEnabled())
                .active(user.isActive())
                .twoFactorEnabled(user.isTwoFactorEnabled())
                .emailNotifications(user.isEmailNotifications())
                .smsNotifications(user.isSmsNotifications())
                .locale(user.getLocale())
                .createdAt(user.getCreatedAt())
                .build();
    }

    @Data
    @Builder
    public static class UpdateRequest {
        @NotBlank(message = "First name is required")
        private String firstName;

        @NotBlank(message = "Last name is required")
        private String lastName;

        @NotBlank(message = "Phone number is required")
        @Pattern(regexp = "^[+]?[(]?[0-9]{1,4}[)]?[-\\s\\.]?[(]?[0-9]{1,4}[)]?[-\\s\\.]?[0-9]{1,9}$", message = "Invalid phone number")
        private String phone;

        private String locale;
        private boolean emailNotifications;
        private boolean smsNotifications;
    }
}
