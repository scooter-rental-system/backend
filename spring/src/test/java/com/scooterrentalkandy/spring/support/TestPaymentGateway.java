package com.scooterrentalkandy.spring.support;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import com.scooterrentalkandy.spring.services.PaymentGateway;

/**
 * TEST-ONLY payment provider double. It lives under src/test, so it is never packaged with the application. It
 * records every provider call and returns outcomes the test chooses; no network or real provider is involved.
 * Like Stripe, a repeated idempotency key returns the first call's result without moving money again.
 *
 * <p>Webhook deliveries: {@link #deliver(PaymentGateway.Event)} registers an event and returns the payload to POST;
 * {@link #parseWebhook} accepts it only with {@link #SIGNATURE}, standing in for the provider's signature check.
 * Call {@link #reset()} before each test: the bean is shared by every test in the application context.
 */
public class TestPaymentGateway implements PaymentGateway {

    public static final String SIGNATURE = "test-signature";

    public record Customer(String id, UUID userId, String email, String idempotencyKey) {
    }

    public record CreatedIntent(String id, BigDecimal amount, String currency, String customerId,
                                Map<String, String> metadata, String idempotencyKey) {
    }

    public record Charge(String customerId, String paymentMethodRef, BigDecimal amount, String currency,
                         String idempotencyKey, ChargeResult result) {
    }

    public record Refund(String intentId, BigDecimal amount, String reason, String idempotencyKey,
                         ChargeResult result) {
    }

    private final AtomicInteger sequence = new AtomicInteger();
    private final Map<String, Event> deliveries = new ConcurrentHashMap<>();
    private final List<Customer> customers = new ArrayList<>();
    private final Map<String, CreatedIntent> intents = new LinkedHashMap<>();
    private final List<String> cancelled = new ArrayList<>();
    private final Set<String> notCancelable = new HashSet<>();
    private final Map<String, RuntimeException> cancelFailures = new HashMap<>();
    private final Map<String, Charge> charges = new LinkedHashMap<>();
    private final Map<String, Refund> refunds = new LinkedHashMap<>();
    private int refundCalls;
    private ChargeResult nextCharge;
    private RuntimeException chargeFailure;
    private Outcome refundOutcome = Outcome.SUCCEEDED;
    private RuntimeException refundFailure;

    public synchronized void reset() {
        deliveries.clear();
        customers.clear();
        intents.clear();
        cancelled.clear();
        notCancelable.clear();
        cancelFailures.clear();
        charges.clear();
        refunds.clear();
        refundCalls = 0;
        nextCharge = null;
        chargeFailure = null;
        refundOutcome = Outcome.SUCCEEDED;
        refundFailure = null;
    }

    // ── Test controls ─────────────────────────────────────────────────────────

    /** New off-session charges are declined with {@code reason} until reset. */
    public synchronized void declineCharges(String reason) {
        nextCharge = ChargeResult.failed(null, reason);
    }

    /** New off-session charges come back as still processing until reset. */
    public synchronized void chargesProcessing() {
        nextCharge = ChargeResult.pending(null);
    }

    /** New off-session charges throw {@code error} (outcome unknown) until reset. */
    public synchronized void failCharges(RuntimeException error) {
        chargeFailure = error;
    }

    /** New refunds get {@code outcome} until reset. */
    public synchronized void refundOutcome(Outcome outcome) {
        refundOutcome = outcome;
    }

    /** Refund calls throw {@code error} (outcome unknown) until reset. */
    public synchronized void failRefunds(RuntimeException error) {
        refundFailure = error;
    }

    /** Cancelling {@code intentId} throws {@code error} (a provider outage). */
    public synchronized void failCancel(String intentId, RuntimeException error) {
        cancelFailures.put(intentId, error);
    }

    /** {@code intentId} can no longer be cancelled (it succeeded or is processing at the provider). */
    public synchronized void notCancelable(String intentId) {
        notCancelable.add(intentId);
    }

    public synchronized List<Customer> customers() {
        return List.copyOf(customers);
    }

    public synchronized List<CreatedIntent> intents() {
        return List.copyOf(intents.values());
    }

    public synchronized List<String> cancelled() {
        return List.copyOf(cancelled);
    }

    /** Charges made at the provider (one per idempotency key). */
    public synchronized List<Charge> charges() {
        return List.copyOf(charges.values());
    }

    /** Refunds made at the provider (one per idempotency key). */
    public synchronized List<Refund> refunds() {
        return List.copyOf(refunds.values());
    }

    /** Refund requests received, including repeats of an idempotency key. */
    public synchronized int refundCalls() {
        return refundCalls;
    }

    // ── Webhook events ────────────────────────────────────────────────────────

    /** Registers {@code event} as a provider delivery and returns its payload. */
    public String deliver(Event event) {
        String payload = "{\"delivery\":" + sequence.incrementAndGet() + "}";
        deliveries.put(payload, event);
        return payload;
    }

    private String eventId() {
        return "evt_test_" + sequence.incrementAndGet();
    }

    public Event succeeded(CreatedIntent intent, String paymentMethodRef, String last4) {
        return succeeded(intent.id(), intent.amount(), intent.currency(), intent.metadata(), paymentMethodRef, last4);
    }

    public Event succeeded(String intentId, BigDecimal amount, String currency, Map<String, String> metadata,
                           String paymentMethodRef, String last4) {
        return new Event(eventId(), Event.SUCCEEDED, intentId, amount, currency, paymentMethodRef, last4, null,
                metadata, null);
    }

    public Event failed(CreatedIntent intent, String reason) {
        return new Event(eventId(), Event.FAILED, intent.id(), intent.amount(), intent.currency(), null, null, reason,
                intent.metadata(), null);
    }

    public Event canceled(CreatedIntent intent) {
        return new Event(eventId(), Event.CANCELED, intent.id(), intent.amount(), intent.currency(), null, null, null,
                intent.metadata(), null);
    }

    public Event refunded(String intentId, BigDecimal amount, BigDecimal amountRefunded, String currency) {
        return new Event(eventId(), Event.CHARGE_REFUNDED, intentId, amount, currency, null, null, null, Map.of(),
                amountRefunded);
    }

    // ── PaymentGateway ────────────────────────────────────────────────────────

    @Override
    public String name() {
        return "test";
    }

    @Override
    public String publishableKey() {
        return "test-publishable-key";
    }

    @Override
    public synchronized String createCustomer(UUID userId, String email, String name, String idempotencyKey) {
        for (Customer c : customers) {
            if (c.idempotencyKey().equals(idempotencyKey)) {
                return c.id();
            }
        }
        Customer c = new Customer("cus_test_" + sequence.incrementAndGet(), userId, email, idempotencyKey);
        customers.add(c);
        return c.id();
    }

    @Override
    public synchronized Intent createIntent(BigDecimal amount, String currency, String customerId, String description,
                                            Map<String, String> metadata, String idempotencyKey) {
        for (CreatedIntent i : intents.values()) {
            if (i.idempotencyKey().equals(idempotencyKey)) {
                return new Intent(i.id(), i.id() + "_client_secret");
            }
        }
        String id = "pi_test_" + sequence.incrementAndGet();
        intents.put(id, new CreatedIntent(id, amount, currency, customerId, Map.copyOf(metadata), idempotencyKey));
        return new Intent(id, id + "_client_secret");
    }

    @Override
    public synchronized boolean cancelIntent(String intentId) {
        RuntimeException failure = cancelFailures.get(intentId);
        if (failure != null) {
            throw failure;
        }
        if (notCancelable.contains(intentId)) {
            return false;
        }
        cancelled.add(intentId);
        return true;
    }

    @Override
    public synchronized ChargeResult chargeSaved(String customerId, String paymentMethodRef, BigDecimal amount,
                                                 String currency, String description, Map<String, String> metadata,
                                                 String idempotencyKey) {
        Charge earlier = charges.get(idempotencyKey);
        if (earlier != null) {
            return earlier.result();
        }
        if (chargeFailure != null) {
            throw chargeFailure;
        }
        String id = "pi_test_charge_" + sequence.incrementAndGet();
        ChargeResult result = nextCharge == null ? ChargeResult.ok(id)
                : new ChargeResult(nextCharge.outcome(), id, nextCharge.failureReason());
        charges.put(idempotencyKey, new Charge(customerId, paymentMethodRef, amount, currency, idempotencyKey, result));
        return result;
    }

    @Override
    public synchronized ChargeResult refund(String intentId, BigDecimal amount, String currency, String reason,
                                            String idempotencyKey) {
        refundCalls++;
        Refund earlier = refunds.get(idempotencyKey);
        if (earlier != null) {
            return earlier.result();
        }
        if (refundFailure != null) {
            throw refundFailure;
        }
        String id = "re_test_" + sequence.incrementAndGet();
        ChargeResult result = new ChargeResult(refundOutcome, id,
                refundOutcome == Outcome.FAILED ? "Refund declined by the test provider" : null);
        refunds.put(idempotencyKey, new Refund(intentId, amount, reason, idempotencyKey, result));
        return result;
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
