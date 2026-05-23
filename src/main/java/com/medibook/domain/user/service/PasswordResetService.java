package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.mail.TransactionalEmailService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final String PREFIX        = "pwd-reset:";
    private static final Duration RESET_TTL    = Duration.ofMinutes(15);
    /** Invite tokens get a longer TTL — a new staff member shouldn't be locked out by a 15-min window. */
    private static final Duration INVITE_TTL   = Duration.ofDays(7);

    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionalEmailService transactionalEmailService;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public String createToken(Long userId) {
        return createToken(userId, RESET_TTL);
    }

    /** Long-lived token used for first-login invites (admin-provisioned doctors). */
    public String createInviteToken(Long userId) {
        return createToken(userId, INVITE_TTL);
    }

    private String createToken(Long userId, Duration ttl) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, String.valueOf(userId), ttl);
        return token;
    }

    public Long validateAndConsume(String token) {
        Object value = redisTemplate.opsForValue().getAndDelete(PREFIX + token);
        if (value == null) {
            throw new MediBookException(
                    "Invalid or expired password reset link",
                    HttpStatus.BAD_REQUEST, "RESET_TOKEN_INVALID");
        }
        return Long.parseLong(value.toString());
    }

    @Async
    public void sendResetEmail(String toEmail, String token) {
        try {
            // Match the FE route: App.tsx mounts MobResetPassword at /reset-password, not /auth/reset-password.
            String link = frontendUrl + "/reset-password?token=" + token;
            transactionalEmailService.sendHtml(
                    toEmail,
                    "Reset your MediBook password",
                    buildResetEmailBody(link));
            log.info("Password reset email dispatched to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }

    /**
     * Send a "Welcome — set up your password" email to a newly-provisioned staff
     * account. Uses the same reset-password redeem endpoint with a longer-lived
     * token. The FE picks up {@code mode=setup} to swap in onboarding copy.
     */
    @Async
    public void sendInviteEmail(String toEmail, String fullName, String role, String token) {
        try {
            String link = frontendUrl + "/reset-password?token=" + token + "&mode=setup";
            transactionalEmailService.sendHtml(
                    toEmail,
                    "Welcome to MediBook — set up your account",
                    buildInviteEmailBody(fullName, role, link));
            log.info("Invite email dispatched to {} ({})", toEmail, role);
        } catch (Exception e) {
            log.error("Failed to send invite email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildResetEmailBody(String link) {
        return """
                <html><body style="font-family:Arial,sans-serif;padding:24px;color:#333">
                  <h2 style="color:#1a73e8">MediBook — Password Reset</h2>
                  <p>We received a request to reset your password. Click the button below to proceed.
                     This link expires in <strong>15 minutes</strong>.</p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;background:#1a73e8;color:#fff;
                            border-radius:4px;text-decoration:none;font-weight:bold;margin:16px 0">
                    Reset Password
                  </a>
                  <p style="color:#999;font-size:12px">
                    If you did not request a password reset, you can safely ignore this email.
                    Your password will not change.
                  </p>
                </body></html>
                """.formatted(link);
    }

    private String buildInviteEmailBody(String fullName, String role, String link) {
        String greetingName = (fullName == null || fullName.isBlank()) ? "there" : fullName;
        String roleLine = (role == null || role.isBlank()) ? "" :
                "<p>Your account has been provisioned as a <strong>" + role + "</strong>.</p>";
        return """
                <html><body style="font-family:Arial,sans-serif;padding:24px;color:#333">
                  <h2 style="color:#0a6">Welcome to MediBook, %s</h2>
                  %s
                  <p>An administrator has created an account for you. Click below to choose a
                     password and finish signing in. This link expires in <strong>7 days</strong>.</p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;background:#0a6;color:#fff;
                            border-radius:4px;text-decoration:none;font-weight:bold;margin:16px 0">
                    Set up my password
                  </a>
                  <p style="color:#999;font-size:12px">
                    If you weren't expecting this, you can safely ignore the email and the
                    account will remain disabled.
                  </p>
                </body></html>
                """.formatted(greetingName, roleLine, link);
    }
}
