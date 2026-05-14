package com.medibook.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class NotificationMetrics {

    private final Counter notificationSentSuccess;
    private final Counter notificationRetryAttempt;
    private final Counter notificationFailedPermanent;

    public NotificationMetrics(MeterRegistry registry) {
        this.notificationSentSuccess = Counter.builder("notification.sent.success")
            .description("Total successful notifications sent")
            .register(registry);

        this.notificationRetryAttempt = Counter.builder("notification.retry.attempt")
            .description("Total notification retry attempts")
            .register(registry);

        this.notificationFailedPermanent = Counter.builder("notification.failed.permanent")
            .description("Total notifications failed permanently")
            .register(registry);
    }

    public void recordSuccess() {
        notificationSentSuccess.increment();
    }

    public void recordRetry() {
        notificationRetryAttempt.increment();
    }

    public void recordPermanentFailure() {
        notificationFailedPermanent.increment();
    }
}
