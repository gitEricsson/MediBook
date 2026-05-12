package com.medibook.common.mail;

public record OutboundEmail(
        String toEmail,
        String subject,
        String htmlBody
) {
}
