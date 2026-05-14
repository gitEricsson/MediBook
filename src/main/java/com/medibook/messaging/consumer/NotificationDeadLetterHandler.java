package com.medibook.messaging.consumer;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class NotificationDeadLetterHandler {

    @KafkaListener(topics = "notification.events-dlt")
    public void handleDeadLetter(String message) {
        log.error("Notification failed after all retries: {}", message);
        // Could send admin alert, store for manual intervention, etc.
        // For now, just log so operators can investigate
    }
}
