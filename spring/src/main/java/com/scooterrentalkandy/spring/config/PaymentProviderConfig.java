package com.scooterrentalkandy.spring.config;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import com.scooterrentalkandy.spring.services.StripePaymentGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the payment provider, failing closed. {@code app.payments.provider} (PAYMENTS_PROVIDER):
 * <ul>
 *   <li>empty: no provider bean; payment operations answer 503. Nothing is simulated.</li>
 *   <li>{@code stripe}: the Stripe gateway. Startup fails if a Stripe key is missing.</li>
 *   <li>anything else (including "mock"): startup fails.</li>
 * </ul>
 */
@Configuration
public class PaymentProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(PaymentProviderConfig.class);

    public static final String STRIPE = "stripe";

    private final AppProperties.Payments payments;

    public PaymentProviderConfig(AppProperties props) {
        this.payments = props.payments();
        String provider = payments == null || payments.provider() == null ? "" : payments.provider();
        if (provider.isBlank()) {
            log.warn("No payment provider configured (PAYMENTS_PROVIDER): card payments are unavailable");
        } else if (!STRIPE.equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported payment provider '" + provider + "' (PAYMENTS_PROVIDER). "
                    + "The only supported provider is 'stripe'; leave it empty to run without card payments.");
        } else {
            requireStripeKeys(payments.stripe());
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.payments.provider", havingValue = STRIPE)
    StripePaymentGateway stripePaymentGateway(Clock clock) {
        AppProperties.Payments.Stripe stripe = payments.stripe();
        return new StripePaymentGateway(StripePaymentGateway.client(stripe.secretKey()), stripe.publishableKey(),
                stripe.webhookSecret(), clock);
    }

    /** Names the missing settings; never prints a value. */
    private static void requireStripeKeys(AppProperties.Payments.Stripe stripe) {
        List<String> missing = new ArrayList<>();
        if (stripe == null || isBlank(stripe.secretKey())) {
            missing.add("STRIPE_SECRET_KEY");
        }
        if (stripe == null || isBlank(stripe.publishableKey())) {
            missing.add("STRIPE_PUBLISHABLE_KEY");
        }
        if (stripe == null || isBlank(stripe.webhookSecret())) {
            missing.add("STRIPE_WEBHOOK_SECRET");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("PAYMENTS_PROVIDER is 'stripe' but " + String.join(", ", missing)
                    + (missing.size() == 1 ? " is" : " are") + " not set. Configure them in the environment.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
