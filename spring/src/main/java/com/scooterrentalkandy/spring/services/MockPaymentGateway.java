package com.scooterrentalkandy.spring.services;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.scooterrentalkandy.spring.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MOCK of Stripe for local development (SDS 7.2: development uses mock/test mode). It plays the part of
 * Stripe's servers: holds payment intents in memory, accepts confirmations from the browser carrying a test
 * payment-method token (never card numbers), and emits signed {@code payment_intent.*} webhook events.
 *
 * <p>Test payment methods, named after Stripe's test tokens:
 * <ul>
 *   <li>{@code pm_card_visa}, {@code pm_card_mastercard}: succeed</li>
 *   <li>{@code pm_card_chargeDeclined}: declined</li>
 *   <li>{@code pm_card_chargeDeclinedInsufficientFunds}: insufficient funds</li>
 *   <li>{@code pm_card_chargeCustomerFail}: the first (on-session) payment succeeds, later off-session charges
 *       for the remaining balance are declined</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "mock", matchIfMissing = true)
public class MockPaymentGateway implements PaymentGateway {

    private static final Logger log = LoggerFactory.getLogger(MockPaymentGateway.class);

    static final String CUSTOMER_FAIL = "pm_card_chargeCustomerFail";
    private static final Map<String, String> DECLINES = Map.of(
            "pm_card_chargeDeclined", "Your card was declined.",
            "pm_card_chargeDeclinedInsufficientFunds", "Your card has insufficient funds.");
    private static final java.util.Set<String> SUCCEEDS =
            java.util.Set.of("pm_card_visa", "pm_card_mastercard", CUSTOMER_FAIL);

    /** Result of a browser confirmation, plus the webhook delivery Stripe would send. */
    public record Confirmation(String status, String error, String webhookPayload, String webhookSignature) {
    }

    private static final class MockIntent {
        final String id;
        final String clientSecret;
        final long amountMinor;
        final String currency;
        final Map<String, String> metadata;
        volatile String status = "requires_payment_method";

        MockIntent(String id, String clientSecret, long amountMinor, String currency, Map<String, String> metadata) {
            this.id = id;
            this.clientSecret = clientSecret;
            this.amountMinor = amountMinor;
            this.currency = currency;
            this.metadata = metadata;
        }
    }

    private final Map<String, MockIntent> intents = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final String webhookSecret;
    private final Clock clock;

    public MockPaymentGateway(ObjectMapper json, AppProperties props, Clock clock) {
        this.json = json;
        this.webhookSecret = props.payments().webhookSecret();
        this.clock = clock;
    }

    @Override
    public String name() {
        return "mock";
    }

    @Override
    public Intent createIntent(BigDecimal amount, String currency, String description, Map<String, String> metadata) {
        String id = "pi_mock_" + random();
        MockIntent intent = new MockIntent(id, id + "_secret_" + random(), toMinor(amount), currency,
                Map.copyOf(metadata));
        intents.put(id, intent);
        log.info("[MOCK STRIPE] payment intent {} for {} {} ({})", id, currency, amount, description);
        return new Intent(id, intent.clientSecret);
    }

    @Override
    public void cancelIntent(String intentId) {
        MockIntent intent = intents.get(intentId);
        if (intent != null && !"succeeded".equals(intent.status)) {
            intent.status = "canceled";
        }
    }

    /** What Stripe.js {@code confirmCardPayment} does against Stripe's API, called by {link MockStripeController}. */
    public Confirmation confirm(String intentId, String clientSecret, String paymentMethod, String last4) {
        MockIntent intent = intents.get(intentId);
        if (intent == null || !intent.clientSecret.equals(clientSecret)) {
            throw new IllegalArgumentException("No such payment intent");
        }
        if (!"requires_payment_method".equals(intent.status)) {
            throw new IllegalStateException("This payment intent is " + intent.status);
        }
        String failure = DECLINES.get(paymentMethod);
        if (failure == null && !SUCCEEDS.contains(paymentMethod)) {
            failure = "Unknown test payment method";
        }
        String type;
        if (failure == null) {
            intent.status = "succeeded";
            type = Event.SUCCEEDED;
        } else {
            type = Event.FAILED; // Stripe keeps the intent open so the customer can try another card.
        }
        String payload = eventPayload(intent, type, paymentMethod, last4, failure);
        log.info("[MOCK STRIPE] confirm {} with {} -> {}", intentId, paymentMethod, failure == null ? "succeeded" : failure);
        return new Confirmation(failure == null ? "succeeded" : "requires_payment_method", failure, payload,
                WebhookSignature.sign(payload, webhookSecret, clock));
    }

    @Override
    public ChargeResult chargeSaved(String paymentMethodRef, BigDecimal amount, String currency, String description,
                                    String idempotencyKey) {
        ChargeResult result;
        if (paymentMethodRef == null) {
            result = ChargeResult.failed("No saved payment method");
        } else if (CUSTOMER_FAIL.equals(paymentMethodRef) || DECLINES.containsKey(paymentMethodRef)) {
            result = ChargeResult.failed("Your card was declined.");
        } else {
            result = ChargeResult.ok("pi_mock_" + random());
        }
        log.info("[MOCK STRIPE] off-session charge {} {} with {} -> {}", currency, amount, paymentMethodRef,
                result.success() ? result.reference() : result.failureReason());
        return result;
    }

    @Override
    public ChargeResult refund(String paymentReference, BigDecimal amount, String reason) {
        String ref = "re_mock_" + random();
        log.info("[MOCK STRIPE] refund {} of {} ({}) -> {}", amount, paymentReference, reason, ref);
        return ChargeResult.ok(ref);
    }

    @Override
    public Event parseWebhook(String payload, String signatureHeader) {
        WebhookSignature.verify(payload, signatureHeader, webhookSecret, clock);
        try {
            JsonNode root = json.readTree(payload);
            JsonNode obj = root.path("data").path("object");
            Map<String, String> metadata = new HashMap<>();
            obj.path("metadata").properties()
                    .forEach(e -> metadata.put(e.getKey(), e.getValue().asText()));
            JsonNode error = obj.path("last_payment_error");
            return new Event(root.path("type").asText(), obj.path("id").asText(),
                    BigDecimal.valueOf(obj.path("amount").asLong(), 2), textOrNull(obj.path("payment_method")),
                    textOrNull(obj.path("card_last4")), error.isMissingNode() ? null : textOrNull(error.path("message")),
                    metadata);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Malformed event");
        }
    }

    private String eventPayload(MockIntent intent, String type, String paymentMethod, String last4, String failure) {
        Map<String, Object> object = new LinkedHashMap<>();
        object.put("id", intent.id);
        object.put("object", "payment_intent");
        object.put("amount", intent.amountMinor);
        object.put("currency", intent.currency.toLowerCase());
        object.put("status", intent.status);
        object.put("payment_method", paymentMethod);
        object.put("card_last4", last4);
        object.put("metadata", intent.metadata);
        if (failure != null) {
            object.put("last_payment_error", Map.of("message", failure));
        }
        try {
            return json.writeValueAsString(Map.of("id", "evt_mock_" + random(), "type", type,
                    "data", Map.of("object", object)));
        } catch (JacksonException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long toMinor(BigDecimal amount) {
        return amount.movePointRight(2).longValueExact();
    }

    private static String textOrNull(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private static String random() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    }
}
