package com.medibook.common.exception;

/**
 * Indicates a transient failure that should trigger a retry.
 * Examples: network timeouts, rate limit hit (temporarily), service unavailable.
 */
public class TemporaryFailureException extends RuntimeException {
    public TemporaryFailureException(String message) {
        super(message);
    }

    public TemporaryFailureException(String message, Throwable cause) {
        super(message, cause);
    }
}
