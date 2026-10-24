package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/**
 * The payment provider (SDS 4.2, 6.2): Stripe PaymentIntents. Card details never reach this API: the browser
 * confirms an intent directly with Stripe using the intent's client secret, and Stripe reports the outcome
 * through a signed webhook. {@link StripePaymentGateway} is the only production implementation; without a
 * configured provider there is no gateway and payment operations are unavailable.
 *
 * <p>Calls with side effects take an idempotency key derived from the business operation, so a retry after a
 * rolled-back transaction repeats nothing at the provider. Amounts are in the major unit of {@code currency}.
 * An outcome the provider did not report (network failure, outage) is thrown as {@link PaymentProviderException},
 * never guessed.
 */
public interface PaymentGateway {

    /** PaymentIntent id and the client secret the browser needs to confirm it. */
    record Intent(String id, String clientSecret) {
    }

    enum Outcome { SUCCEEDED, PENDING, FAILED }

    /** The provider's answer to a charge or refund; {@code reference} is its id (payment intent or refund). */
    record ChargeResult(Outcome outcome, String reference, String failureReason) {

        public static ChargeResult ok(String reference) {
            return new ChargeResult(Outcome.SUCCEEDED, reference, null);
        }

        public static ChargeResult pending(String reference) {
            return new ChargeResult(Outcome.PENDING, reference, null);
        }

        public static ChargeResult failed(String reference, String reason) {
            return new ChargeResult(Outcome.FAILED, reference, reason);
        }

        public boolean succeeded() {
            return outcome == Outcome.SUCCEEDED;
        }
    }

    /**
     * A webhook event whose signature has been verified. Payment-intent events carry the intent; a
     * {@code charge.refunded} event carries the charge's intent and the total refunded so far.
     */
    record Event(String id, String type, String intentId, BigDecimal amount, String currency,
                 String paymentMethodRef, String cardLast4, String failureReason, Map<String, String> metadata,
                 BigDecimal amountRefunded) {

        public static final String SUCCEEDED = "payment_intent.succeeded";
        public static final String FAILED = "payment_intent.payment_failed";
        public static final String CANCELED = "payment_intent.canceled";
        public static final String CHARGE_REFUNDED = "charge.refunded";

        public boolean succeeded() {
            return SUCCEEDED.equals(type);
        }
    }

    String name();

    /** The key the browser uses to load the provider's payment form; public by design. */
    String publishableKey();

    /** Stripe: {@code Customer.create}. Returns the customer id. */
    String createCustomer(UUID userId, String email, String name, String idempotencyKey);

    /**
     * Stripe: {@code PaymentIntent.create} for {@code customerId} with {@code setup_future_usage=off_session}, so
     * the payment method is saved and can be charged again for the remaining balance when the rental ends.
     */
    Intent createIntent(BigDecimal amount, String currency, String customerId, String description,
                        Map<String, String> metadata, String idempotencyKey);

    /**
     * Stripe: {@code PaymentIntent.cancel}. Returns false if the intent can no longer be cancelled because it has
     * succeeded or is being processed; an intent that is already cancelled counts as cancelled.
     */
    boolean cancelIntent(String intentId);

    /** Stripe: an off-session PaymentIntent confirmed with the customer's saved payment method. */
    ChargeResult chargeSaved(String customerId, String paymentMethodRef, BigDecimal amount, String currency,
                             String description, Map<String, String> metadata, String idempotencyKey);

    /** Stripe: {@code Refund.create} against a payment intent. */
    ChargeResult refund(String intentId, BigDecimal amount, String currency, String reason, String idempotencyKey);

    /** Verifies the signature and reads the event. Throws {@link IllegalArgumentException} if it is not genuine. */
    Event parseWebhook(String payload, String signatureHeader);
}
