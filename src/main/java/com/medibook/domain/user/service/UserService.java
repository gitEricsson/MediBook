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
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.Set;

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
        if (newRole == Role.ROLE_ADMIN || newRole == Role.ROLE_SUPER_ADMIN) {
            throw new MediBookException("Cannot promote to ADMIN or SUPER_ADMIN via this endpoint",
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

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public UserResponse updateProfile(Long userId, UserResponse.UpdateRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        user.setFirstName(request.getFirstName());
        user.setLastName(request.getLastName());
        user.setPhone(request.getPhone());
        if (request.getLocale() != null) user.setLocale(request.getLocale());
        user.setEmailNotifications(request.isEmailNotifications());
        user.setSmsNotifications(request.isSmsNotifications());

        return UserResponse.fromUser(userRepository.save(user));
    }

    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final long MAX_AVATAR_BYTES = 2 * 1024 * 1024; // 2 MB

    @CacheEvict(value = "users", key = "#userId")
    @Transactional
    public UserResponse uploadAvatar(Long userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new MediBookException("Avatar file is required", HttpStatus.BAD_REQUEST, "MISSING_FILE");
        }
        if (file.getSize() > MAX_AVATAR_BYTES) {
            throw new MediBookException("Avatar must be smaller than 2 MB", HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE");
        }
        String mimeType = file.getContentType();
        if (mimeType == null || !ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new MediBookException("Only JPEG, PNG, and WEBP images are allowed", HttpStatus.BAD_REQUEST, "UNSUPPORTED_MEDIA_TYPE");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", userId));

        try {
            byte[] bytes = file.getBytes();
            String dataUri = "data:" + mimeType + ";base64," + Base64.getEncoder().encodeToString(bytes);
            user.setAvatarUrl(dataUri);
        } catch (Exception e) {
            throw new MediBookException("Failed to process avatar image", HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED");
        }

        return UserResponse.fromUser(userRepository.save(user));
    }
}
