package com.medibook.config;

import jakarta.mail.internet.MimeMessage;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
@Profile("test")
public class TestMailConfig {

    @Bean
    public JavaMailSender javaMailSender() {
        return new JavaMailSenderImpl() {
            @Override
            public void send(MimeMessage mimeMessage) {
            }

            @Override
            public void send(MimeMessage... mimeMessages) {
            }

            @Override
            public void send(SimpleMailMessage simpleMessage) {
            }

            @Override
            public void send(SimpleMailMessage... simpleMessages) {
            }
        };
    }
}
