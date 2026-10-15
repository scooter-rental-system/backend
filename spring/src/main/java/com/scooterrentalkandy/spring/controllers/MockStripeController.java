package com.scooterrentalkandy.spring.controllers;

import java.util.Map;
import java.util.Set;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.services.MockPaymentGateway;
import com.scooterrentalkandy.spring.services.PaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * MOCK of Stripe's public confirm endpoint, served outside {@code /api} and only when the mock provider is
 * active. The browser's mock "Stripe.js" turns a test card into a payment-method token and sends only that
 * token here, as real Stripe.js does; any other field (e.g. a card number) is rejected. After confirming, it
 * delivers the signed webhook to our webhook handler, as Stripe would.
 */
@RestController
@ConditionalOnProperty(name = "app.payments.provider", havingValue = "mock", matchIfMissing = true)
public class MockStripeController {

    private static final Set<String> ALLOWED = Set.of("clientSecret", "paymentMethod", "cardLast4");

    private final MockPaymentGateway gateway;
    private final PaymentService payments;

    public MockStripeController(MockPaymentGateway gateway, PaymentService payments) {
        this.gateway = gateway;
        this.payments = payments;
    }

    @PostMapping("/mock-stripe/v1/payment_intents/{intentId}/confirm")
    public Map<String, Object> confirm(@PathVariable String intentId, @RequestBody Map<String, Object> body) {
        if (!ALLOWED.containsAll(body.keySet())) {
            throw ApiException.badRequest("Only a payment-method token may be sent; card details are not accepted");
        }
        String secret = text(body, "clientSecret");
        String method = text(body, "paymentMethod");
        String last4 = text(body, "cardLast4");
        if (secret == null || method == null || !method.matches("pm_[A-Za-z_]+")
                || (last4 != null && !last4.matches("\\d{4}"))) {
            throw ApiException.badRequest("clientSecret and a pm_ payment-method token are required");
        }
        MockPaymentGateway.Confirmation c;
        try {
            c = gateway.confirm(intentId, secret, method, last4);
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Payment intent");
        } catch (IllegalStateException e) {
            throw new ApiException(HttpStatus.CONFLICT, e.getMessage());
        }
        payments.handleWebhook(c.webhookPayload(), c.webhookSignature());
        return c.error() == null ? Map.of("status", c.status()) : Map.of("status", c.status(), "error", c.error());
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v instanceof String s && !s.isBlank() ? s : null;
    }
}
