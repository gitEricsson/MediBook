package com.medibook.common.mail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("TransactionalEmailService - Unit Tests")
class TransactionalEmailServiceTest {

    @Mock
    SmtpMailTransport smtpMailTransport;

    @Mock
    BrevoMailTransport brevoMailTransport;

    @Test
    @DisplayName("sendHtml - sends via SMTP when SMTP is available")
    void sendHtml_sendsViaSmtpWhenAvailable() throws Exception {
        MailProperties properties = new MailProperties();
        TransactionalEmailService service =
                new TransactionalEmailService(smtpMailTransport, brevoMailTransport, properties);

        when(smtpMailTransport.isAvailable()).thenReturn(true);

        service.sendHtml("user@medibook.com", "Subject", "<p>Hello</p>");

        verify(smtpMailTransport).send(new OutboundEmail("user@medibook.com", "Subject", "<p>Hello</p>"));
        verify(brevoMailTransport, never()).send(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("sendHtml - falls back to Brevo when SMTP errors")
    void sendHtml_fallsBackToBrevoWhenSmtpFails() throws Exception {
        MailProperties properties = new MailProperties();
        properties.setFailoverEnabled(true);
        TransactionalEmailService service =
                new TransactionalEmailService(smtpMailTransport, brevoMailTransport, properties);

        when(smtpMailTransport.isAvailable()).thenReturn(true);
        when(brevoMailTransport.isAvailable()).thenReturn(true);
        doThrow(new RuntimeException("smtp down"))
                .when(smtpMailTransport)
                .send(org.mockito.ArgumentMatchers.any());

        service.sendHtml("user@medibook.com", "Subject", "<p>Hello</p>");

        verify(brevoMailTransport).send(new OutboundEmail("user@medibook.com", "Subject", "<p>Hello</p>"));
    }

    @Test
    @DisplayName("sendHtml - uses Brevo when SMTP is unavailable")
    void sendHtml_usesBrevoWhenSmtpUnavailable() throws Exception {
        MailProperties properties = new MailProperties();
        properties.setFailoverEnabled(true);
        TransactionalEmailService service =
                new TransactionalEmailService(smtpMailTransport, brevoMailTransport, properties);

        when(smtpMailTransport.isAvailable()).thenReturn(false);
        when(brevoMailTransport.isAvailable()).thenReturn(true);

        service.sendHtml("user@medibook.com", "Subject", "<p>Hello</p>");

        verify(brevoMailTransport).send(new OutboundEmail("user@medibook.com", "Subject", "<p>Hello</p>"));
    }
}
