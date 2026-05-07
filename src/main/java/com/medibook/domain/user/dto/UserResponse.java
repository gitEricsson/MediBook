package com.medibook.domain.user.dto;

import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
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
                .twoFactorEnabled(user.isTwoFactorEnabled())
                .emailNotifications(user.isEmailNotifications())
                .smsNotifications(user.isSmsNotifications())
                .locale(user.getLocale())
                .createdAt(user.getCreatedAt())
                .build();
    }
}
