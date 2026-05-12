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

    private static final String PREFIX    = "pwd-reset:";
    private static final Duration TOKEN_TTL = Duration.ofMinutes(15);

    private final RedisTemplate<String, Object> redisTemplate;
    private final TransactionalEmailService transactionalEmailService;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, String.valueOf(userId), TOKEN_TTL);
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
            String link = frontendUrl + "/auth/reset-password?token=" + token;
            transactionalEmailService.sendHtml(
                    toEmail,
                    "Reset your MediBook password",
                    buildEmailBody(link));
            log.info("Password reset email dispatched to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send password reset email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildEmailBody(String link) {
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
}
