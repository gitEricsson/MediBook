package com.medibook.domain.user.service;

import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.infrastructure.metrics.TokenMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * Manages session timeout logic based on user inactivity.
 *
 * Key responsibilities:
 * - Check if a session has exceeded the inactivity timeout threshold
 * - Update lastActivityAt for active sessions
 * - Revoke all tokens when timeout is exceeded
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionTimeoutService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;
    private final TokenMetrics tokenMetrics;

    @Value("${app.security.session.timeout-minutes:30}")
    private int sessionTimeoutMinutes;

    /**
     * Check if the user's session has timed out due to inactivity.
     * If timeout exceeded, revokes all active tokens.
     *
     * @param userId the user ID to check
     * @return true if session is still valid, false if timed out
     */
    @Transactional
    public boolean isSessionValid(Long userId) {
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty()) {
            return false;
        }

        User user = userOptional.get();
        LocalDateTime lastActivityAt = user.getLastActivityAt();

        // If lastActivityAt is not set, use createdAt
        if (lastActivityAt == null) {
            lastActivityAt = user.getCreatedAt();
        }

        // Check if inactivity exceeds timeout threshold
        long minutesInactive = ChronoUnit.MINUTES.between(lastActivityAt, LocalDateTime.now());

        if (minutesInactive > sessionTimeoutMinutes) {
            log.warn("Session timeout for user [{}]: inactive for {} minutes (threshold: {} minutes)",
                    userId, minutesInactive, sessionTimeoutMinutes);
            // Revoke all active tokens for the user
            int revokedCount = refreshTokenService.revokeAllForUser(userId, "session_timeout");
            log.info("Revoked {} session(s) for user [{}] due to inactivity timeout", revokedCount, userId);
            // Record session timeout metric
            tokenMetrics.recordSessionTimeout(userId, minutesInactive);
            return false;
        }

        return true;
    }

    /**
     * Update the user's last activity timestamp.
     * Called on each authenticated API request.
     *
     * @param userId the user ID whose activity to update
     */
    @Transactional
    public void updateActivity(Long userId) {
        userRepository.findById(userId).ifPresent(user -> {
            user.setLastActivityAt(LocalDateTime.now());
            userRepository.save(user);
        });
    }

    /**
     * Get the session timeout duration in minutes.
     *
     * @return timeout duration in minutes
     */
    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    /**
     * Get the remaining time before session timeout for a user.
     *
     * @param userId the user ID to check
     * @return remaining minutes, or -1 if user not found or already timed out
     */
    public long getRemainingSessionMinutes(Long userId) {
        Optional<User> userOptional = userRepository.findById(userId);
        if (userOptional.isEmpty()) {
            return -1;
        }

        User user = userOptional.get();
        LocalDateTime lastActivityAt = user.getLastActivityAt();

        if (lastActivityAt == null) {
            lastActivityAt = user.getCreatedAt();
        }

        long minutesInactive = ChronoUnit.MINUTES.between(lastActivityAt, LocalDateTime.now());
        long remaining = sessionTimeoutMinutes - minutesInactive;

        return Math.max(remaining, -1);
    }
}
