package com.medibook.domain.user.service;

import com.medibook.audit.entity.AuditLog;
import com.medibook.audit.service.AuditLogService;
import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.user.dto.UserResponse;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository    userRepository;
    private final AuthService       authService;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService   auditLogService;

    @Cacheable(value = "users", key = "#id")
    @Bulkhead(name = "patientService")
    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {
        return userRepository.findById(id)
                .map(UserResponse::fromUser)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", id));
    }

    @Transactional(readOnly = true)
    public Page<UserResponse> getAllUsers(Pageable pageable) {
        return userRepository.findAll(pageable).map(UserResponse::fromUser);
    }

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public void enableTwoFactor(Long userId) {
        authService.enableTwoFactor(userId);
    }

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public void disableUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setEnabled(false);
        userRepository.save(user);
        refreshTokenService.revokeAllForUser(userId);
    }

    @Transactional(readOnly = true)
    public UserResponse getByEmail(String email) {
        return userRepository.findByEmail(email)
                .map(UserResponse::fromUser)
                .orElseThrow(() -> new ResourceNotFoundException("User", "email", email));
    }

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public UserResponse enableUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setEnabled(true);
        return UserResponse.fromUser(userRepository.save(user));
    }

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public UserResponse changeRole(Long userId, Role newRole) {
        if (newRole == Role.ROLE_ADMIN) {
            throw new MediBookException("Cannot promote to ADMIN via this endpoint",
                    HttpStatus.FORBIDDEN, "ADMIN_PROMOTION_DENIED");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));
        user.setRole(newRole);
        return UserResponse.fromUser(userRepository.save(user));
    }

    public int revokeAllSessions(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", "id", userId);
        }
        return refreshTokenService.revokeAllForUser(userId);
    }

    public List<AuditLog> getAuditLog(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new ResourceNotFoundException("User", "id", userId);
        }
        return auditLogService.getRecentByActor(userId);
    }
}
