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

    private static final String OTP_KEY_PREFIX     = "otp:code:";
    private static final String OTP_GEN_PREFIX     = "otp:gen:";
    private static final String OTP_FAIL_PREFIX    = "otp:fail:";
    private static final int    MAX_GEN_PER_WINDOW = 3;
    private static final int    MAX_VERIFY_FAILS   = 5;
    private static final long   LOCKOUT_MINUTES    = 15;

    private final SecureRandom secureRandom = new SecureRandom();

    private final RedisTemplate<String, Object> redisTemplate;
    private final JavaMailSender mailSender;

    @Value("${app.otp.expiration-minutes:5}")
    private long otpExpirationMinutes;

    @Value("${spring.mail.username}")
    private String fromAddress;


    public String generateAndStore(String email) {
        String genKey = OTP_GEN_PREFIX + email;
        Long genCount = redisTemplate.opsForValue().increment(genKey);
        if (genCount != null && genCount == 1) {
            redisTemplate.expire(genKey, Duration.ofMinutes(otpExpirationMinutes));
        }
        if (genCount != null && genCount > MAX_GEN_PER_WINDOW) {
            throw new MediBookException(
                    "Too many OTP requests. Please wait " + otpExpirationMinutes + " minutes before retrying.",
                    HttpStatus.TOO_MANY_REQUESTS, "OTP_RATE_LIMITED");
        }

        String otp = String.format("%06d", secureRandom.nextInt(1_000_000));
        redisTemplate.opsForValue().set(
                OTP_KEY_PREFIX + email,
                otp,
                Duration.ofMinutes(otpExpirationMinutes));
        return otp;
    }


    public void verify(String email, String otp) {
        String codeKey = OTP_KEY_PREFIX + email;
        String failKey = OTP_FAIL_PREFIX + email;

        Object failCount = redisTemplate.opsForValue().get(failKey);
        if (failCount != null && Integer.parseInt(failCount.toString()) >= MAX_VERIFY_FAILS) {
            throw new MediBookException(
                    "Account temporarily locked due to too many failed attempts. Try again in " + LOCKOUT_MINUTES + " minutes.",
                    HttpStatus.TOO_MANY_REQUESTS, "OTP_LOCKED");
        }

        Object stored = redisTemplate.opsForValue().get(codeKey);
        if (stored == null) {
            throw new MediBookException("OTP expired or not found", HttpStatus.UNAUTHORIZED, "OTP_EXPIRED");
        }
        if (!stored.toString().equals(otp)) {
            Long fails = redisTemplate.opsForValue().increment(failKey);
            if (fails != null && fails == 1) {
                redisTemplate.expire(failKey, Duration.ofMinutes(LOCKOUT_MINUTES));
            }
            throw new MediBookException("Invalid OTP", HttpStatus.UNAUTHORIZED, "OTP_INVALID");
        }

        redisTemplate.delete(codeKey);
        redisTemplate.delete(failKey);
    }


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
