package com.medibook.domain.common.service;

import com.medibook.domain.common.dto.ContactRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ContactService {

    private final JavaMailSender mailSender;

    public void sendMessage(ContactRequest request) {
        log.info("Contact message received from [{}] with subject [{}]", request.getEmail(), request.getSubject());
        
        SimpleMailMessage message = new SimpleMailMessage();
        message.setTo("support@medibook.com");
        message.setFrom(request.getEmail());
        message.setSubject("Contact Us: " + request.getSubject());
        message.setText("From: " + request.getName() + " (" + request.getEmail() + ")\n\n" + request.getMessage());
        
        mailSender.send(message);
        log.info("Contact email sent successfully");
    }
}
