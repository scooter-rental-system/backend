package com.scooterrentalkandy.spring.services;

/**
 * The payment provider could not be reached or did not report an outcome, so whether the call took effect is
 * unknown. The transaction rolls back; a retry reuses the same idempotency key, so nothing is done twice.
 */
public class PaymentProviderException extends RuntimeException {

    public PaymentProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
