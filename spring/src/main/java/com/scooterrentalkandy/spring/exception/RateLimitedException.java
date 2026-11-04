package com.scooterrentalkandy.spring.exception;

import java.time.Duration;

/** Too many requests of one kind from one client; answered with 429, Retry-After and the given message. */
public class RateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public RateLimitedException(String message, Duration retryAfter) {
        super(message);
        this.retryAfter = retryAfter;
    }

    /** Whole seconds until another request is allowed, at least 1. */
    public long retryAfterSeconds() {
        return Math.max(1, (retryAfter.toMillis() + 999) / 1000);
    }
}
