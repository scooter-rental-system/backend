package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Payment provider shaped like Stripe PaymentIntents (SDS 4.2, 6.2). Card details never reach this API: the
 * browser confirms an intent directly with the provider using the intent's client secret, and the provider
 * reports the outcome through a signed webhook. {@link MockPaymentGateway} simulates Stripe locally; a
 * {@code StripeGateway} would implement the same methods with the Stripe Java SDK and be selected with
 * {@code app.payments.provider}.
 */
public interface PaymentGateway {

    /** Stripe PaymentIntent id and the client secret the browser needs to confirm it. */
    record Intent(String id, String clientSecret) {
    }

    record ChargeResult(boolean success, String reference, String failureReason) {

        public static ChargeResult ok(String reference) {
            return new ChargeResult(true, reference, null);
        }

        public static ChargeResult failed(String reason) {
            return new ChargeResult(false, null, reason);
        }
    }

    /** A webhook event whose signature has been verified. */
    record Event(String type, String intentId, BigDecimal amount, String paymentMethodRef, String cardLast4,
                 String failureReason, Map<String, String> metadata) {

        public static final String SUCCEEDED = "payment_intent.succeeded";
        public static final String FAILED = "payment_intent.payment_failed";

        public boolean succeeded() {
            return SUCCEEDED.equals(type);
        }
    }

    String name();

    /**
     * Stripe: {@code PaymentIntent.create} with {@code setup_future_usage=off_session}, so the payment method
     * can be charged again for the remaining balance when the rental ends.
     */
    Intent createIntent(BigDecimal amount, String currency, String description, Map<String, String> metadata);

    /** Stripe: {@code PaymentIntent.cancel}. Safe to call on an intent that is already finished. */
    void cancelIntent(String intentId);

    /** Stripe: an off-session PaymentIntent confirmed with the saved payment method. */
    ChargeResult chargeSaved(String paymentMethodRef, BigDecimal amount, String currency, String description,
                             String idempotencyKey);

    /** Stripe: {@code Refund.create} against a payment intent. */
    ChargeResult refund(String paymentReference, BigDecimal amount, String reason);

    /** Stripe: {@code Webhook.constructEvent}. Throws {@link IllegalArgumentException} if the signature is bad. */
    Event parseWebhook(String payload, String signatureHeader);
}
