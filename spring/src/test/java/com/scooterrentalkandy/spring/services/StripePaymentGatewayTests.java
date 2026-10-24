package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import com.stripe.StripeClient;
import com.stripe.net.Webhook;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The Stripe gateway through the official SDK against a local stub of the Stripe API on 127.0.0.1: the SDK's real
 * request encoding, idempotency headers and error handling are exercised, and nothing leaves the machine. Webhook
 * signatures are made with the SDK's own signing helper and the gateway's fixed clock. All keys are placeholders.
 */
class StripePaymentGatewayTests {

    private static final String WEBHOOK_SECRET = "webhook-secret-placeholder";
    private static final Instant NOW = Instant.parse("2026-10-24T09:00:00Z");

    private record Request(String method, String path, Map<String, String> form, String idempotencyKey) {
    }

    private record Response(int status, String json) {
    }

    private HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private StripePaymentGateway gateway;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        StripeClient client = StripeClient.builder()
                .setApiKey("stub-api-key-not-a-secret")
                .setApiBase("http://127.0.0.1:" + server.getAddress().getPort())
                .setMaxNetworkRetries(0)
                .build();
        gateway = new StripePaymentGateway(client, "publishable-placeholder", WEBHOOK_SECRET,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> form = new java.util.LinkedHashMap<>();
        for (String pair : body.isEmpty() ? new String[0] : body.split("&")) {
            String[] kv = pair.split("=", 2);
            form.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                    kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        requests.add(new Request(method, path, form, exchange.getRequestHeaders().getFirst("Idempotency-Key")));
        Response response = responses.getOrDefault(method + " " + path,
                new Response(404, "{\"error\":{\"type\":\"invalid_request_error\",\"message\":\"No stub\"}}"));
        byte[] out = response.json().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(response.status(), out.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(out);
        }
    }

    private void stub(String methodAndPath, int status, String json) {
        responses.put(methodAndPath, new Response(status, json));
    }

    private Request only(String method, String path) {
        List<Request> matching = requests.stream().filter(r -> r.method().equals(method) && r.path().equals(path))
                .toList();
        assertThat(matching).hasSize(1);
        return matching.getFirst();
    }

    private static String intentJson(String id, String status) {
        return """
                {"id": "%s", "object": "payment_intent", "client_secret": "%s_secret_stub", "status": "%s",
                 "amount": 25000, "currency": "lkr"}""".formatted(id, id, status);
    }

    private static String error(String type, String code, String message, String intentStatus) {
        String intent = intentStatus == null ? "" : """
                , "payment_intent": {"id": "pi_err", "object": "payment_intent", "status": "%s"}"""
                .formatted(intentStatus);
        return """
                {"error": {"type": "%s", "code": "%s", "message": "%s"%s}}""".formatted(type, code, message, intent);
    }

    // ── Calls to Stripe ───────────────────────────────────────────────────────

    @Test
    void createsAPaymentIntentInLkrMinorUnitsWithTheIdempotencyKey() {
        stub("POST /v1/payment_intents", 200, intentJson("pi_1", "requires_payment_method"));

        PaymentGateway.Intent intent = gateway.createIntent(new BigDecimal("250.00"), "LKR", "cus_1",
                "Scooter Rent Kandy booking BK-1", Map.of("bookingId", "b-1", "purpose", "INITIAL"),
                "booking-b-1-initial-intent-1");

        assertThat(intent.id()).isEqualTo("pi_1");
        assertThat(intent.clientSecret()).isEqualTo("pi_1_secret_stub");
        Request sent = only("POST", "/v1/payment_intents");
        assertThat(sent.idempotencyKey()).isEqualTo("booking-b-1-initial-intent-1");
        assertThat(sent.form()).containsEntry("amount", "25000").containsEntry("currency", "lkr")
                .containsEntry("customer", "cus_1").containsEntry("setup_future_usage", "off_session")
                .containsEntry("allowed_payment_method_types[0]", "card")
                .containsEntry("metadata[bookingId]", "b-1").containsEntry("metadata[purpose]", "INITIAL");
    }

    @Test
    void createsACustomerWithTheIdempotencyKey() {
        stub("POST /v1/customers", 200, """
                {"id": "cus_1", "object": "customer"}""");
        UUID userId = UUID.randomUUID();

        assertThat(gateway.createCustomer(userId, "rider@example.test", "Rider One", "customer-" + userId))
                .isEqualTo("cus_1");

        Request sent = only("POST", "/v1/customers");
        assertThat(sent.idempotencyKey()).isEqualTo("customer-" + userId);
        assertThat(sent.form()).containsEntry("email", "rider@example.test").containsEntry("name", "Rider One")
                .containsEntry("metadata[userId]", userId.toString());
    }

    @Test
    void cancellingReportsWhetherTheIntentIsClosed() {
        stub("POST /v1/payment_intents/pi_open/cancel", 200, intentJson("pi_open", "canceled"));
        stub("POST /v1/payment_intents/pi_paid/cancel", 400, error("invalid_request_error",
                "payment_intent_unexpected_state", "This PaymentIntent has already succeeded", "succeeded"));
        stub("POST /v1/payment_intents/pi_gone/cancel", 400, error("invalid_request_error",
                "payment_intent_unexpected_state", "This PaymentIntent has already been canceled", "canceled"));
        stub("POST /v1/payment_intents/pi_down/cancel", 500, error("api_error", "server_error", "Try again", null));

        assertThat(gateway.cancelIntent("pi_open")).isTrue();
        assertThat(gateway.cancelIntent("pi_paid")).isFalse();
        assertThat(gateway.cancelIntent("pi_gone")).isTrue();
        assertThatThrownBy(() -> gateway.cancelIntent("pi_down")).isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void chargesTheSavedCardOffSession() {
        stub("POST /v1/payment_intents", 200, intentJson("pi_charge", "succeeded"));

        PaymentGateway.ChargeResult result = gateway.chargeSaved("cus_1", "pm_1", new BigDecimal("430.50"), "LKR",
                "balance", Map.of("purpose", "BALANCE"), "booking-b-1-balance-charge-1");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.reference()).isEqualTo("pi_charge");
        Request sent = only("POST", "/v1/payment_intents");
        assertThat(sent.idempotencyKey()).isEqualTo("booking-b-1-balance-charge-1");
        assertThat(sent.form()).containsEntry("amount", "43050").containsEntry("customer", "cus_1")
                .containsEntry("payment_method", "pm_1").containsEntry("off_session", "true")
                .containsEntry("confirm", "true");
    }

    @Test
    void aDeclinedOrUnauthenticatedChargeIsAFailureNotAnOutage() {
        stub("POST /v1/payment_intents", 402, error("card_error", "card_declined", "Your card was declined.",
                "requires_payment_method"));

        PaymentGateway.ChargeResult declined = gateway.chargeSaved("cus_1", "pm_1", BigDecimal.TEN, "LKR", "balance",
                Map.of(), "key-declined");

        assertThat(declined.outcome()).isEqualTo(PaymentGateway.Outcome.FAILED);
        assertThat(declined.failureReason()).isEqualTo("Your card was declined.");
        assertThat(declined.reference()).isEqualTo("pi_err");

        stub("POST /v1/payment_intents", 200, intentJson("pi_3ds", "requires_action"));
        assertThat(gateway.chargeSaved("cus_1", "pm_1", BigDecimal.TEN, "LKR", "balance", Map.of(), "key-3ds")
                .outcome()).isEqualTo(PaymentGateway.Outcome.FAILED);
        stub("POST /v1/payment_intents", 200, intentJson("pi_slow", "processing"));
        assertThat(gateway.chargeSaved("cus_1", "pm_1", BigDecimal.TEN, "LKR", "balance", Map.of(), "key-slow")
                .outcome()).isEqualTo(PaymentGateway.Outcome.PENDING);
    }

    @Test
    void anUnreachableProviderIsAnUnknownOutcome() {
        stub("POST /v1/payment_intents", 503, error("api_error", "server_error", "Unavailable", null));
        assertThatThrownBy(() -> gateway.chargeSaved("cus_1", "pm_1", BigDecimal.TEN, "LKR", "balance", Map.of(),
                "key-503")).isInstanceOf(PaymentProviderException.class);

        server.stop(0);
        assertThatThrownBy(() -> gateway.refund("pi_1", BigDecimal.TEN, "LKR", "reason", "key-down"))
                .isInstanceOf(PaymentProviderException.class);
    }

    @Test
    void refundsAgainstTheIntentWithTheIdempotencyKey() {
        stub("POST /v1/refunds", 200, """
                {"id": "re_1", "object": "refund", "status": "succeeded", "amount": 75000}""");

        PaymentGateway.ChargeResult result = gateway.refund("pi_1", new BigDecimal("750.00"), "LKR",
                "Booking BK-1 cancelled", "refund-pi_1-full");

        assertThat(result.succeeded()).isTrue();
        assertThat(result.reference()).isEqualTo("re_1");
        Request sent = only("POST", "/v1/refunds");
        assertThat(sent.idempotencyKey()).isEqualTo("refund-pi_1-full");
        assertThat(sent.form()).containsEntry("payment_intent", "pi_1").containsEntry("amount", "75000");

        stub("POST /v1/refunds", 200, """
                {"id": "re_2", "object": "refund", "status": "pending", "amount": 100}""");
        assertThat(gateway.refund("pi_1", BigDecimal.ONE, "LKR", "r", "key-2").outcome())
                .isEqualTo(PaymentGateway.Outcome.PENDING);
        stub("POST /v1/refunds", 400, error("invalid_request_error", "charge_already_refunded",
                "Charge has already been refunded.", null));
        PaymentGateway.ChargeResult refused = gateway.refund("pi_1", BigDecimal.ONE, "LKR", "r", "key-3");
        assertThat(refused.outcome()).isEqualTo(PaymentGateway.Outcome.FAILED);
        assertThat(refused.failureReason()).isEqualTo("Charge has already been refunded.");
    }

    // ── Webhooks: verified with the SDK ───────────────────────────────────────

    private static String signed(String payload, long timestamp) throws Exception {
        return Webhook.Signature.generateSignatureHeader(payload, WEBHOOK_SECRET, timestamp);
    }

    private static String intentEvent(String type, String extra) {
        return """
                {"id": "evt_1", "object": "event", "api_version": "%s", "type": "%s",
                 "data": {"object": {"id": "pi_1", "object": "payment_intent", "amount": 25000, "currency": "lkr",
                  "status": "succeeded", "payment_method": "pm_1",
                  "metadata": {"bookingId": "b-1", "purpose": "INITIAL"}%s}}}"""
                .formatted(com.stripe.Stripe.API_VERSION, type, extra);
    }

    @Test
    void aSignedSuccessEventIsReadWithTheAmountInLkrAndTheCard() throws Exception {
        stub("GET /v1/payment_methods/pm_1", 200, """
                {"id": "pm_1", "object": "payment_method", "type": "card", "card": {"last4": "4242"}}""");
        String payload = intentEvent("payment_intent.succeeded", "");

        PaymentGateway.Event event = gateway.parseWebhook(payload, signed(payload, NOW.getEpochSecond()));

        assertThat(event.id()).isEqualTo("evt_1");
        assertThat(event.type()).isEqualTo(PaymentGateway.Event.SUCCEEDED);
        assertThat(event.intentId()).isEqualTo("pi_1");
        assertThat(event.amount()).isEqualTo(new BigDecimal("250.00"));
        assertThat(event.currency()).isEqualTo("LKR");
        assertThat(event.paymentMethodRef()).isEqualTo("pm_1");
        assertThat(event.cardLast4()).isEqualTo("4242");
        assertThat(event.metadata()).containsEntry("bookingId", "b-1").containsEntry("purpose", "INITIAL");
    }

    @Test
    void failedCancelledAndRefundedEventsAreRead() throws Exception {
        String failed = intentEvent("payment_intent.payment_failed",
                ", \"last_payment_error\": {\"message\": \"Your card was declined.\"}");
        PaymentGateway.Event f = gateway.parseWebhook(failed, signed(failed, NOW.getEpochSecond()));
        assertThat(f.type()).isEqualTo(PaymentGateway.Event.FAILED);
        assertThat(f.failureReason()).isEqualTo("Your card was declined.");
        assertThat(f.cardLast4()).isNull();

        String canceled = intentEvent("payment_intent.canceled", "");
        assertThat(gateway.parseWebhook(canceled, signed(canceled, NOW.getEpochSecond())).type())
                .isEqualTo(PaymentGateway.Event.CANCELED);

        String refunded = """
                {"id": "evt_2", "object": "event", "api_version": "%s", "type": "charge.refunded",
                 "data": {"object": {"id": "ch_1", "object": "charge", "amount": 75000, "amount_refunded": 30000,
                  "currency": "lkr", "payment_intent": "pi_1", "refunded": false}}}"""
                .formatted(com.stripe.Stripe.API_VERSION);
        PaymentGateway.Event r = gateway.parseWebhook(refunded, signed(refunded, NOW.getEpochSecond()));
        assertThat(r.type()).isEqualTo(PaymentGateway.Event.CHARGE_REFUNDED);
        assertThat(r.intentId()).isEqualTo("pi_1");
        assertThat(r.amount()).isEqualTo(new BigDecimal("750.00"));
        assertThat(r.amountRefunded()).isEqualTo(new BigDecimal("300.00"));
    }

    @Test
    void anEventThatIsNotSignedWithTheWebhookSecretIsRejected() throws Exception {
        String payload = intentEvent("payment_intent.succeeded", "");
        String tampered = payload.replace("25000", "1");

        assertThatThrownBy(() -> gateway.parseWebhook(tampered, signed(payload, NOW.getEpochSecond())))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Signature verification failed");
        String otherSecret = Webhook.Signature.generateSignatureHeader(payload, "another-secret", NOW.getEpochSecond());
        assertThatThrownBy(() -> gateway.parseWebhook(payload, otherSecret))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(payload, "t=1,v1=forged"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> gateway.parseWebhook(payload, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Missing signature");
        assertThat(requests).isEmpty();
    }

    @Test
    void aReplayedEventOutsideTheToleranceIsRejected() throws Exception {
        String payload = intentEvent("payment_intent.succeeded", "");
        long tenMinutesAgo = NOW.minusSeconds(600).getEpochSecond();

        assertThatThrownBy(() -> gateway.parseWebhook(payload, signed(payload, tenMinutesAgo)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void otherEventTypesAreReadWithoutAnIntent() throws Exception {
        String payload = """
                {"id": "evt_3", "object": "event", "api_version": "%s", "type": "customer.created",
                 "data": {"object": {"id": "cus_1", "object": "customer"}}}""".formatted(com.stripe.Stripe.API_VERSION);

        PaymentGateway.Event event = gateway.parseWebhook(payload, signed(payload, NOW.getEpochSecond()));

        assertThat(event.type()).isEqualTo("customer.created");
        assertThat(event.intentId()).isNull();
    }
}
