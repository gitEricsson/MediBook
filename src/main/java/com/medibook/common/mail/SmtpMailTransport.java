package com.medibook.common.mail;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class SmtpMailTransport {

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    @Value("${spring.mail.host:}")
    private String host;

    @Value("${spring.mail.username:}")
    private String username;

    @Value("${spring.mail.password:}")
    private String password;

    @Value("${spring.mail.properties.mail.smtp.auth:false}")
    private boolean authRequired;

    public boolean isAvailable() {
        if (!StringUtils.hasText(mailProperties.getFromAddress()) || !StringUtils.hasText(host)) {
            return false;
        }
        return !authRequired || (StringUtils.hasText(username) && StringUtils.hasText(password));
    }

    public void send(OutboundEmail email) throws Exception {
        MimeMessage mimeMessage = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, true, "UTF-8");
        helper.setFrom(new InternetAddress(mailProperties.getFromAddress(), mailProperties.getFromName()));
        helper.setTo(email.toEmail());
        helper.setSubject(email.subject());
        helper.setText(email.htmlBody(), true);
        mailSender.send(mimeMessage);
    }
}
