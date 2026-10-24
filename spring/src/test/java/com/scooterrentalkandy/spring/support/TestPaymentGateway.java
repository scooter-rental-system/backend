package com.scooterrentalkandy.spring.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.scooterrentalkandy.spring.services.PaymentGateway;

/**
 * TEST-ONLY payment provider double. It lives under src/test, so it is never packaged with the application. It
 * records every provider call and returns outcomes the test chooses; nothing is simulated by default for the
 * application, and no network or real provider is involved.
 *
 * <p>Webhook deliveries: {@link #deliver(PaymentGateway.Event)} registers an event and returns the payload to POST;
 * {@link #parseWebhook} accepts it only with {@link #SIGNATURE}, standing in for the provider's signature check.
 * Call {@link #reset()} before each test: the bean is shared by every test in the application context.
 */
public class TestPaymentGateway implements PaymentGateway {

    public static final String SIGNATURE = "test-signature";

    public record CreatedIntent(String id, BigDecimal amount, String currency, Map<String, String> metadata) {
    }

    public record Charge(String paymentMethodRef, BigDecimal amount, String currency, String idempotencyKey) {
    }

    public record Refund(String paymentReference, BigDecimal amount, String reason) {
    }

    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Event> deliveries = new ConcurrentHashMap<>();
    private final List<CreatedIntent> intents = new ArrayList<>();
    private final List<String> cancelled = new ArrayList<>();
    private final List<Charge> charges = new ArrayList<>();
    private final List<Refund> refunds = new ArrayList<>();
    private final Map<String, RuntimeException> cancelFailures = new HashMap<>();
    private volatile String chargeDecline;
    private volatile String refundFailure;

    public synchronized void reset() {
        deliveries.clear();
        intents.clear();
        cancelled.clear();
        charges.clear();
        refunds.clear();
        cancelFailures.clear();
        chargeDecline = null;
        refundFailure = null;
    }

    // ── Test controls ─────────────────────────────────────────────────────────

    /** Off-session charges are declined with {@code reason} until reset. */
    public void declineCharges(String reason) {
        chargeDecline = reason;
    }

    /** Refunds fail with {@code reason} until reset. */
    public void failRefunds(String reason) {
        refundFailure = reason;
    }

    /** Cancelling {@code intentId} throws {@code error} (a provider outage). */
    public synchronized void failCancel(String intentId, RuntimeException error) {
        cancelFailures.put(intentId, error);
    }

    public synchronized List<CreatedIntent> intents() {
        return List.copyOf(intents);
    }

    public synchronized List<String> cancelled() {
        return List.copyOf(cancelled);
    }

    public synchronized List<Charge> charges() {
        return List.copyOf(charges);
    }

    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds);
    }

    /** Registers {@code event} as a provider delivery and returns its payload. */
    public String deliver(Event event) {
        String payload = "{\"delivery\":\"evt_test_" + sequence.incrementAndGet() + "\"}";
        deliveries.put(payload, event);
        return payload;
    }

    public static Event succeeded(CreatedIntent intent, String paymentMethodRef, String last4) {
        return new Event(Event.SUCCEEDED, intent.id(), intent.amount(), paymentMethodRef, last4, null,
                intent.metadata());
    }

    public static Event failed(CreatedIntent intent, String reason) {
        return new Event(Event.FAILED, intent.id(), intent.amount(), null, null, reason, intent.metadata());
    }

    // ── PaymentGateway ────────────────────────────────────────────────────────

    @Override
    public String name() {
        return "test";
    }

    @Override
    public synchronized Intent createIntent(BigDecimal amount, String currency, String description,
                                            Map<String, String> metadata) {
        String id = "pi_test_" + sequence.incrementAndGet();
        intents.add(new CreatedIntent(id, amount, currency, Map.copyOf(metadata)));
        return new Intent(id, id + "_client_secret");
    }

    @Override
    public synchronized void cancelIntent(String intentId) {
        RuntimeException failure = cancelFailures.get(intentId);
        if (failure != null) {
            throw failure;
        }
        cancelled.add(intentId);
    }

    @Override
    public synchronized ChargeResult chargeSaved(String paymentMethodRef, BigDecimal amount, String currency,
                                                 String description, String idempotencyKey) {
        charges.add(new Charge(paymentMethodRef, amount, currency, idempotencyKey));
        return chargeDecline == null ? ChargeResult.ok("pi_test_charge_" + sequence.incrementAndGet())
                : ChargeResult.failed(chargeDecline);
    }

    @Override
    public synchronized ChargeResult refund(String paymentReference, BigDecimal amount, String reason) {
        refunds.add(new Refund(paymentReference, amount, reason));
        return refundFailure == null ? ChargeResult.ok("re_test_" + sequence.incrementAndGet())
                : ChargeResult.failed(refundFailure);
    }

    @Override
    public Event parseWebhook(String payload, String signatureHeader) {
        if (!SIGNATURE.equals(signatureHeader)) {
            throw new IllegalArgumentException("Signature mismatch");
        }
        Event event = deliveries.get(payload);
        if (event == null) {
            throw new IllegalArgumentException("Malformed event");
        }
        return event;
    }
}
