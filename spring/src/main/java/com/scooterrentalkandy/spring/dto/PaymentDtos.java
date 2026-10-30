package com.scooterrentalkandy.spring.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
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

    /**
     * Which client-side payment library to load. {@code available} is false when no provider is configured; then
     * {@code provider} and {@code publishableKey} are null and payments cannot be made.
     */
    public record PaymentConfigResponse(boolean available, String provider, String publishableKey, String currency) {
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
            LocalDateTime paidAt,
            LocalDateTime createdAt,
            /** Provider calls (or recorded cash) for this payment; included for a single booking, null in lists. */
            List<TransactionResponse> transactions,
            /** Cash, QR or bank transfer only: money still owed back to the customer; null when none. */
            BigDecimal refundDue,
            /**
             * Every way money was received, in order and once each, e.g. [QR_TRANSFER, CASH] when the final balance
             * was paid in cash after a QR payment; {@code method} stays the first.
             */
            List<PaymentMethod> paymentMethods) {
    }

    /**
     * An admin confirms cash handed over at the rental counter (received, or given back). {@code amount} is the
     * amount the admin saw on the booking; it must be the amount due, so a confirmation from an out-of-date screen
     * is refused.
     */
    public record CashConfirmRequest(
            @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal amount) {
    }

    /**
     * {@code method}: how this amount moved. {@code recordedBy}: the admin who recorded or approved a manual amount or
     * refund; null for provider transactions. {@code reference} and {@code note}: a transfer reference, and an admin's
     * note on a manual refund.
     */
    public record TransactionResponse(UUID id, PaymentTransaction.Kind kind, String providerRef, BigDecimal amount,
                                      PaymentTransaction.Status status, String failureReason, LocalDateTime createdAt,
                                      String recordedBy, PaymentMethod method, String reference, String note) {
    }
}
