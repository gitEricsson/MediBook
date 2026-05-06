package com.medibook.domain.user.service;

import com.medibook.common.exception.MediBookException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    private static final String PREFIX    = "email-verify:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);

    private final RedisTemplate<String, Object> redisTemplate;
    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${app.frontend.url:http://localhost:3000}")
    private String frontendUrl;

    public String createToken(Long userId) {
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(PREFIX + token, String.valueOf(userId), TOKEN_TTL);
        return token;
    }

    public Long validateAndConsume(String token) {
        // getAndDelete is atomic (Redis GETDEL) — prevents concurrent double-use of the same token
        Object value = redisTemplate.opsForValue().getAndDelete(PREFIX + token);
        if (value == null) {
            throw new MediBookException(
                    "Invalid or expired email verification link",
                    HttpStatus.BAD_REQUEST, "VERIFY_TOKEN_INVALID");
        }
        return Long.parseLong(value.toString());
    }

    @Async
    public void sendVerificationEmail(String toEmail, String token) {
        try {
            String link = frontendUrl + "/auth/verify-email?token=" + token;
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Verify your MediBook email address");
            helper.setText(buildEmailBody(link), true);
            mailSender.send(msg);
            log.info("Verification email dispatched to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send verification email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildEmailBody(String link) {
        return """
                <html><body style="font-family:Arial,sans-serif;padding:24px;color:#333">
                  <h2 style="color:#1a73e8">MediBook — Verify Your Email</h2>
                  <p>Welcome to MediBook! Click the button below to verify your email address.
                     This link expires in <strong>24 hours</strong>.</p>
                  <a href="%s"
                     style="display:inline-block;padding:12px 24px;background:#1a73e8;color:#fff;
                            border-radius:4px;text-decoration:none;font-weight:bold;margin:16px 0">
                    Verify Email
                  </a>
                  <p style="color:#999;font-size:12px">
                    If you did not create a MediBook account, you can safely ignore this email.
                  </p>
                </body></html>
                """.formatted(link);
    }
}
