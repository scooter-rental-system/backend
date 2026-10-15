package com.scooterrentalkandy.spring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;

/** No request type in this API carries card data: cards are confirmed with the payment provider, not with us. */
public final class PaymentDtos {

    private PaymentDtos() {
    }

    /** What the browser needs to confirm a payment intent with the provider (Stripe.js confirmCardPayment). */
    public record PaymentIntentResponse(
            UUID paymentId,
            UUID bookingId,
            String purpose,
            String intentId,
            String clientSecret,
            BigDecimal amount,
            String currency,
            String provider) {
    }

    /** Which client-side payment library to load; {@code publishableKey} is null for the mock provider. */
    public record PaymentConfigResponse(String provider, String publishableKey, String currency) {
    }

    public record PaymentResponse(
            UUID id,
            UUID bookingId,
            String bookingReference,
            String customerName,
            BigDecimal amount,
            PaymentMethod method,
            PaymentStatus status,
            String provider,
            String stripePaymentId,
            String cardLast4,
            BigDecimal balanceDue,
            String failureReason,
            Instant paidAt,
            LocalDateTime createdAt,
            /** Provider calls for this payment; included for a single booking, omitted (null) in lists. */
            List<TransactionResponse> transactions) {
    }

    public record TransactionResponse(UUID id, PaymentTransaction.Kind kind, String providerRef, BigDecimal amount,
                                      PaymentTransaction.Status status, String failureReason, LocalDateTime createdAt) {
    }
}
