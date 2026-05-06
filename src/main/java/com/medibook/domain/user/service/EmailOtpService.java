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

import java.security.SecureRandom;
import java.time.Duration;

/**
 * Email OTP service — generates a 6-digit code, stores it in Redis
 * with a configurable TTL, and sends it via SMTP.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmailOtpService {

    private static final String OTP_KEY_PREFIX = "otp:";
    private final SecureRandom secureRandom = new SecureRandom();

    private final RedisTemplate<String, Object> redisTemplate;
    private final JavaMailSender mailSender;

    @Value("${app.otp.expiration-minutes:5}")
    private long otpExpirationMinutes;

    @Value("${spring.mail.username}")
    private String fromAddress;

    // ─── Generate & Store ────────────────────────────────────────────────────

    public String generateAndStore(String email) {
        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(
                OTP_KEY_PREFIX + email,
                otp,
                Duration.ofMinutes(otpExpirationMinutes));
        return otp;
    }

    // ─── Verify ──────────────────────────────────────────────────────────────

    public void verify(String email, String otp) {
        String key = OTP_KEY_PREFIX + email;
        Object stored = redisTemplate.opsForValue().get(key);

        if (stored == null) {
            throw new MediBookException("OTP expired or not found", HttpStatus.UNAUTHORIZED, "OTP_EXPIRED");
        }
        if (!stored.toString().equals(otp)) {
            throw new MediBookException("Invalid OTP", HttpStatus.UNAUTHORIZED, "OTP_INVALID");
        }

        // Invalidate immediately after successful use
        redisTemplate.delete(key);
    }

    // ─── Send Email ──────────────────────────────────────────────────────────

    @Async
    public void sendOtpEmail(String toEmail, String otp) {
        try {
            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Your MediBook Verification Code");
            helper.setText(buildEmailBody(otp), true);
            mailSender.send(mimeMessage);
            log.info("OTP email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send OTP email to {}: {}", toEmail, e.getMessage());
        }
    }

    private String buildEmailBody(String otp) {
        return """
                <html><body style="font-family:Arial,sans-serif;padding:24px">
                  <h2 style="color:#1a73e8">MediBook — Verification Code</h2>
                  <p>Your one-time verification code is:</p>
                  <div style="font-size:36px;font-weight:bold;letter-spacing:8px;color:#1a73e8;margin:16px 0">%s</div>
                  <p>This code expires in <strong>%d minutes</strong>.</p>
                  <p style="color:#999;font-size:12px">If you did not request this code, please ignore this email.</p>
                </body></html>
                """.formatted(otp, otpExpirationMinutes);
    }
}
