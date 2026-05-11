package com.medibook.config;

import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class SuperAdminBootstrap implements ApplicationRunner {

    @Value("${app.bootstrap.super-admin.email}")
    private String email;

    @Value("${app.bootstrap.super-admin.password}")
    private String password;

    @Value("${app.bootstrap.super-admin.first-name:Super}")
    private String firstName;

    @Value("${app.bootstrap.super-admin.last-name:Admin}")
    private String lastName;

    @Value("${app.bootstrap.super-admin.phone:}")
    private String phone;

    private final UserRepository  userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ROLE_SUPER_ADMIN)) {
            log.info("Super admin already exists — skipping bootstrap");
            return;
        }

        if (email == null || email.isBlank()) {
            throw new IllegalStateException(
                    "SUPER_ADMIN_EMAIL is required. Set the SUPER_ADMIN_EMAIL environment variable.");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "SUPER_ADMIN_PASSWORD is required. Set the SUPER_ADMIN_PASSWORD environment variable.");
        }

        String normalizedEmail = email.trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            log.warn("SUPER_ADMIN_EMAIL '{}' already exists under a different role — skipping bootstrap",
                    normalizedEmail);
            return;
        }

        User superAdmin = User.builder()
                .email(normalizedEmail)
                .password(passwordEncoder.encode(password))
                .firstName(firstName.isBlank() ? "Super" : firstName)
                .lastName(lastName.isBlank()  ? "Admin" : lastName)
                .phone(phone == null || phone.isBlank() ? null : phone.trim())
                .role(Role.ROLE_SUPER_ADMIN)
                .isActive(true)
                .enabled(true)
                .build();

        userRepository.save(superAdmin);
        log.info("Super admin bootstrapped successfully: email={}", normalizedEmail);
    }
}
