package com.scooterrentalkandy.spring.dto;

import java.time.Instant;
import java.util.Map;

/** Body of every error response. {@code fieldErrors}: per-field validation messages, or null. */
public record ErrorResponse(Instant timestamp, int status, String message, Map<String, String> fieldErrors) {
}

