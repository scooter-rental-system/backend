package com.scooterrentalkandy.spring.services;

import java.util.UUID;

/**
 * Published (synchronously, in the same transaction) when the provider confirms a payment, so the booking
 * lifecycle can react without the payment module depending on it.
 */
public final class PaymentEvents {

    private PaymentEvents() {
    }

    /** The initial charge succeeded: the booking can become ACTIVE. */
    public record InitialPaymentSucceeded(UUID bookingId) {
    }

    /** The remaining balance was paid online after a failed completion charge: the booking can complete. */
    public record BalancePaid(UUID bookingId) {
    }
}
