package com.scooterrentalkandy.spring.controllers;

import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BillingDtos.InvoiceResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentConfigResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentIntentResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentResponse;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.services.BillingService;
import com.scooterrentalkandy.spring.services.PaymentService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** No endpoint here accepts card data; cards are confirmed with the payment provider in the browser. */
@RestController
public class PaymentController {

    private final PaymentService payments;
    private final BillingService billing;

    public PaymentController(PaymentService payments, BillingService billing) {
        this.payments = payments;
        this.billing = billing;
    }

    @GetMapping("/api/payments/config")
    public PaymentConfigResponse config() {
        return payments.config();
    }

    /** Initial charge (1 hour + 1 gear day). Returns the client secret to confirm in the browser. */
    @PostMapping("/api/bookings/{id}/payment-intent")
    public PaymentIntentResponse initialIntent(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return payments.createInitialIntent(id, user);
    }

    /** Pay a remaining balance online after the automatic charge at completion failed. */
    @PostMapping("/api/bookings/{id}/balance-intent")
    public PaymentIntentResponse balanceIntent(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return payments.createBalanceIntent(id, user);
    }

    /** Payment provider webhook, authenticated by its signature header. */
    @PostMapping("/api/webhooks/stripe")
    @ResponseStatus(HttpStatus.OK)
    public void webhook(@RequestBody String payload,
                        @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        payments.handleWebhook(payload, signature);
    }

    @GetMapping("/api/invoices/{id}")
    public InvoiceResponse invoice(@PathVariable UUID id, @CurrentUser AuthUser user) {
        return billing.get(id, user);
    }

    @GetMapping("/api/admin/payments")
    public List<PaymentResponse> recentPayments() {
        return payments.recent();
    }

    @GetMapping("/api/admin/invoices")
    public List<InvoiceResponse> recentInvoices() {
        return billing.recent();
    }
}
