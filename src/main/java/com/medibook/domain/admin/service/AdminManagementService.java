package com.medibook.domain.admin.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.admin.dto.CreateAdminRequest;
import com.medibook.domain.admin.dto.ResetAdminPasswordRequest;
import com.medibook.domain.admin.dto.UpdateAdminRequest;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.user.service.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminManagementService {

    private final UserRepository      userRepository;
    private final PasswordEncoder     passwordEncoder;
    private final RefreshTokenService refreshTokenService;

    @Transactional
    public UserResponse createAdmin(CreateAdminRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new MediBookException("Email already in use", HttpStatus.CONFLICT, "EMAIL_TAKEN");
        }

        User admin = User.builder()
                .email(email)
                .password(passwordEncoder.encode(request.getPassword()))
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .phone(request.getPhone())
                .role(Role.ROLE_ADMIN)
                .isActive(true)
                .enabled(true)
                .build();

        User saved = userRepository.save(admin);
        log.info("Admin created: id={} email={}", saved.getId(), saved.getEmail());
        return UserResponse.fromUser(saved);
    }

    @Transactional(readOnly = true)
    public UserResponse getAdmin(Long id) {
        return userRepository.findById(id)
                .filter(u -> u.getRole() == Role.ROLE_ADMIN)
                .map(UserResponse::fromUser)
                .orElseThrow(() -> new ResourceNotFoundException("Admin", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> listAdmins(String q, Pageable pageable) {
        if (q != null && !q.isBlank()) {
            return userRepository.searchByRole(Role.ROLE_ADMIN, q.trim(), pageable)
                    .map(UserResponse::fromUser);
        }
        return userRepository.findByRole(Role.ROLE_ADMIN, pageable)
                .map(UserResponse::fromUser);
    }

    @Transactional
    public UserResponse updateAdmin(Long id, UpdateAdminRequest request) {
        User admin = requireAdmin(id);
        admin.setFirstName(request.getFirstName());
        admin.setLastName(request.getLastName());
        admin.setPhone(request.getPhone());
        return UserResponse.fromUser(userRepository.save(admin));
    }

    @Transactional
    public void activateAdmin(Long id) {
        User admin = requireAdmin(id);
        admin.setEnabled(true);
        admin.setActive(true);
        userRepository.save(admin);
        log.info("Admin activated: id={}", id);
    }

    @Transactional
    public void deactivateAdmin(Long id) {
        User admin = requireAdmin(id);
        admin.setEnabled(false);
        userRepository.save(admin);
        refreshTokenService.revokeAllForUser(id);
        log.info("Admin deactivated: id={}", id);
    }

    @Transactional
    public void deleteAdmin(Long id) {
        User admin = requireAdmin(id);
        admin.setEnabled(false);
        userRepository.save(admin);
        refreshTokenService.revokeAllForUser(id);
        log.info("Admin soft-deleted (disabled): id={}", id);
    }

    @Transactional
    public void resetAdminPassword(Long id, ResetAdminPasswordRequest request) {
        User admin = requireAdmin(id);
        admin.setPassword(passwordEncoder.encode(request.getNewPassword()));
        userRepository.save(admin);
        refreshTokenService.revokeAllForUser(id);
        log.info("Admin password reset: id={}", id);
    }

    private User requireAdmin(Long id) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Admin", "id", id));
        if (user.getRole() != Role.ROLE_ADMIN) {
            throw new MediBookException("Target user is not an admin", HttpStatus.FORBIDDEN, "NOT_ADMIN");
        }
        return user;
    }
}
