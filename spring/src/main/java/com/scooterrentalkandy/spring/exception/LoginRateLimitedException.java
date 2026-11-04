package com.scooterrentalkandy.spring.exception;

import java.time.Duration;

/** Too many failed sign-ins from one address (SDS 6.1); answered with 429 and a Retry-After header. */
public class LoginRateLimitedException extends RuntimeException {

    private final Duration retryAfter;

    public LoginRateLimitedException(Duration retryAfter) {
        super("Too many failed sign-in attempts");
        this.retryAfter = retryAfter;
    }

    /** Whole seconds until another attempt is allowed, at least 1. */
    public long retryAfterSeconds() {
        long millis = retryAfter.toMillis();
        return Math.max(1, (millis + 999) / 1000);
    }
}
