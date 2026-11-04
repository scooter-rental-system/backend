package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import com.stripe.StripeClient;
import com.stripe.exception.CardException;
import com.stripe.exception.EventDataObjectDeserializationException;
import com.stripe.exception.InvalidRequestException;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.PaymentIntent;
import com.stripe.model.PaymentMethod;
import com.stripe.model.Refund;
import com.stripe.model.StripeError;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stripe through the official Stripe Java SDK. Created only by {@code PaymentProviderConfig} when
 * {@code app.payments.provider=stripe} and all Stripe keys are configured. Webhooks are verified with the SDK's
 * {@link Webhook#constructEvent}. Declines and refusals Stripe reports are returned as failed results; when
 * Stripe cannot be reached (or answers with a server error) the outcome is unknown and
 * {@link PaymentProviderException} is thrown.
 */
public class StripePaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentGateway.class);

    /** Stripe's error code when an intent's state does not allow the request (e.g. cancelling a paid intent). */
    private static final String UNEXPECTED_STATE = "payment_intent_unexpected_state";

    private final StripeClient client;
    private final String publishableKey;
    private final String webhookSecret;
    private final Clock clock;

    public StripePaymentGateway(StripeClient client, String publishableKey, String webhookSecret, Clock clock) {
        this.client = client;
        this.publishableKey = publishableKey;
        this.webhookSecret = webhookSecret;
        this.clock = clock;
    }

    /** Network retries are safe: every call with side effects carries its own idempotency key. */
    public static StripeClient client(String secretKey) {
        return StripeClient.builder().setApiKey(secretKey).setMaxNetworkRetries(2).build();
    }

    @Override
    public String name() {
        return "stripe";
    }

    @Override
    public String publishableKey() {
        return publishableKey;
    }

    @Override
    public String createCustomer(UUID userId, String email, String name, String idempotencyKey) {
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(email)
                .setName(name)
                .putMetadata("userId", userId.toString())
                .build();
        try {
            return client.v1().customers().create(params, options(idempotencyKey)).getId();
        } catch (StripeException e) {
            throw unavailable("create a customer", e);
        }
    }

    @Override
    public Intent createIntent(BigDecimal amount, String currency, String customerId, String description,
                               Map<String, String> metadata, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(StripeAmounts.toMinor(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setCustomer(customerId)
                .setSetupFutureUsage(PaymentIntentCreateParams.SetupFutureUsage.OFF_SESSION)
                .addAllowedPaymentMethodType(PaymentIntentCreateParams.AllowedPaymentMethodType.CARD)
                .setDescription(description)
                .putAllMetadata(metadata)
                .build();
        try {
            PaymentIntent intent = client.v1().paymentIntents().create(params, options(idempotencyKey));
            return new Intent(intent.getId(), intent.getClientSecret());
        } catch (StripeException e) {
            throw unavailable("create a payment intent", e);
        }
    }

    @Override
    public boolean cancelIntent(String intentId) {
        try {
            client.v1().paymentIntents().cancel(intentId);
            return true;
        } catch (InvalidRequestException e) {
            if (UNEXPECTED_STATE.equals(e.getCode())) {
                PaymentIntent intent = e.getStripeError() == null ? null : e.getStripeError().getPaymentIntent();
                String status = intent == null ? null : intent.getStatus();
                log.info("Payment intent {} not cancelled: it is {}", intentId, status);
                return "canceled".equals(status);
            }
            throw unavailable("cancel a payment intent", e);
        } catch (StripeException e) {
            throw unavailable("cancel a payment intent", e);
        }
    }

    @Override
    public ChargeResult chargeSaved(String customerId, String paymentMethodRef, BigDecimal amount, String currency,
                                    String description, Map<String, String> metadata, String idempotencyKey) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(StripeAmounts.toMinor(amount, currency))
                .setCurrency(currency.toLowerCase(Locale.ROOT))
                .setCustomer(customerId)
                .setPaymentMethod(paymentMethodRef)
                .setOffSession(true)
                .setConfirm(true)
                .setDescription(description)
                .putAllMetadata(metadata)
                .build();
        try {
            PaymentIntent intent = client.v1().paymentIntents().create(params, options(idempotencyKey));
            return switch (intent.getStatus()) {
                case "succeeded" -> ChargeResult.ok(intent.getId());
                case "processing" -> ChargeResult.pending(intent.getId());
                default -> ChargeResult.failed(intent.getId(), "The card needs the customer's authentication");
            };
        } catch (CardException | InvalidRequestException e) {
            // A decline (including authentication required) or a refusal: Stripe's answer, not an outage.
            PaymentIntent intent = e.getStripeError() == null ? null : e.getStripeError().getPaymentIntent();
            return ChargeResult.failed(intent == null ? null : intent.getId(), reason(e, "The card was declined"));
        } catch (StripeException e) {
            throw unavailable("charge the saved card", e);
        }
    }

    @Override
    public ChargeResult refund(String intentId, BigDecimal amount, String currency, String reason,
                               String idempotencyKey) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(intentId)
                .setAmount(StripeAmounts.toMinor(amount, currency))
                .putMetadata("reason", reason)
                .build();
        try {
            Refund refund = client.v1().refunds().create(params, options(idempotencyKey));
            return switch (refund.getStatus()) {
                case "succeeded" -> ChargeResult.ok(refund.getId());
                case "pending", "requires_action" -> ChargeResult.pending(refund.getId());
                default -> ChargeResult.failed(refund.getId(), refund.getFailureReason() == null
                        ? "Refund " + refund.getStatus() : refund.getFailureReason());
            };
        } catch (InvalidRequestException e) {
            return ChargeResult.failed(null, reason(e, "The refund was refused"));
        } catch (StripeException e) {
            throw unavailable("refund a payment", e);
        }
    }

    @Override
    public Event parseWebhook(String payload, String signatureHeader) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw new IllegalArgumentException("Missing signature");
        }
        com.stripe.model.Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, webhookSecret, Webhook.DEFAULT_TOLERANCE, clock);
        } catch (SignatureVerificationException e) {
            throw new IllegalArgumentException("Signature verification failed");
        } catch (RuntimeException e) { // the SDK reports unreadable JSON with unchecked exceptions
            throw new IllegalArgumentException("Malformed event");
        }
        String type = event.getType();
        return switch (type) {
            case Event.SUCCEEDED, Event.FAILED, Event.CANCELED -> {
                PaymentIntent intent = object(event, PaymentIntent.class);
                String currency = intent.getCurrency().toUpperCase(Locale.ROOT);
                String failure = intent.getLastPaymentError() == null ? null
                        : intent.getLastPaymentError().getMessage();
                yield new Event(event.getId(), type, intent.getId(),
                        StripeAmounts.fromMinor(intent.getAmount(), currency), currency, intent.getPaymentMethod(),
                        Event.SUCCEEDED.equals(type) ? cardLast4(intent.getPaymentMethod()) : null, failure,
                        metadata(intent.getMetadata()), null);
            }
            case Event.CHARGE_REFUNDED -> {
                Charge charge = object(event, Charge.class);
                String currency = charge.getCurrency().toUpperCase(Locale.ROOT);
                yield new Event(event.getId(), type, charge.getPaymentIntent(),
                        StripeAmounts.fromMinor(charge.getAmount(), currency), currency, null, null, null,
                        metadata(charge.getMetadata()), StripeAmounts.fromMinor(charge.getAmountRefunded(), currency));
            }
            default -> new Event(event.getId(), type, null, null, null, null, null, null, Map.of(), null);
        };
    }

    /** The event's object. Falls back to lenient reading when the event's API version differs from the SDK's. */
    private static <T extends StripeObject> T object(com.stripe.model.Event event, Class<T> type) {
        StripeObject object;
        try {
            var data = event.getDataObjectDeserializer();
            object = data.getObject().isPresent() ? data.getObject().get() : data.deserializeUnsafe();
        } catch (EventDataObjectDeserializationException e) {
            throw new IllegalArgumentException("Malformed event");
        }
        if (!type.isInstance(object)) {
            throw new IllegalArgumentException("Unexpected object in " + event.getType());
        }
        return type.cast(object);
    }

    /** Last four digits of the card, for display; not needed for the payment itself. */
    private String cardLast4(String paymentMethodId) {
        if (paymentMethodId == null) {
            return null;
        }
        try {
            PaymentMethod method = client.v1().paymentMethods().retrieve(paymentMethodId);
            return method.getCard() == null ? null : method.getCard().getLast4();
        } catch (StripeException e) {
            log.warn("Could not read the card of payment method {}: {}", paymentMethodId, e.getMessage());
            return null;
        }
    }

    private static Map<String, String> metadata(Map<String, String> metadata) {
        return metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    private static RequestOptions options(String idempotencyKey) {
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private static String reason(StripeException e, String fallback) {
        StripeError error = e.getStripeError();
        return error != null && error.getMessage() != null ? error.getMessage() : fallback;
    }

    private static PaymentProviderException unavailable(String action, StripeException e) {
        log.warn("Stripe could not {}: {} (status {}, request {})", action, e.getMessage(), e.getStatusCode(),
                e.getRequestId());
        return new PaymentProviderException("The payment provider could not " + action + "; please try again", e);
    }
}
