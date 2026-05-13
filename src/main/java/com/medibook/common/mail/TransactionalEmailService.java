package com.medibook.common.mail;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionalEmailService {

    private final SmtpMailTransport smtpMailTransport;
    private final BrevoMailTransport brevoMailTransport;
    private final MailProperties mailProperties;

    public void sendHtml(String toEmail, String subject, String htmlBody) {
        OutboundEmail email = new OutboundEmail(toEmail, subject, htmlBody);

        if (smtpMailTransport.isAvailable()) {
            try {
                smtpMailTransport.send(email);
                log.info("Transactional email sent via SMTP to {}", toEmail);
                return;
            } catch (Exception ex) {
                log.warn("SMTP delivery failed for {}: {}", toEmail, ex.getMessage());
            }
        } else {
            log.info("SMTP delivery unavailable for {}; host/credentials or from-address missing", toEmail);
        }

        if (mailProperties.isFailoverEnabled() && brevoMailTransport.isAvailable()) {
            try {
                brevoMailTransport.send(email);
                return;
            } catch (Exception ex) {
                log.error("Brevo failover delivery failed for {}: {}", toEmail, ex.getMessage());
            }
        }

        log.error("No email provider could deliver message to {}", toEmail);
    }
}
