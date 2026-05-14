package com.medibook.infrastructure.notification;

import com.medibook.common.exception.TemporaryFailureException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

@Configuration
@EnableRetry
public class RetryConfig {

    @Bean
    public RetryTemplate notificationRetryTemplate() {
        RetryTemplate retryTemplate = new RetryTemplate();

        // Retry up to 3 times (initial attempt + 2 retries)
        SimpleRetryPolicy policy = new SimpleRetryPolicy(3,
            Map.of(
                IOException.class, true,                    // Transient: retry
                ConnectException.class, true,               // Transient: retry
                SocketTimeoutException.class, true,         // Transient: retry
                TimeoutException.class, true,               // Transient: retry
                TemporaryFailureException.class, true       // Custom transient
            ));
        retryTemplate.setRetryPolicy(policy);

        // Exponential backoff: 1s, 2s, 4s
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(1000);      // 1 second
        backoff.setMultiplier(2.0);            // Double each time
        backoff.setMaxInterval(10000);         // Max 10 seconds
        retryTemplate.setBackOffPolicy(backoff);

        return retryTemplate;
    }
}
