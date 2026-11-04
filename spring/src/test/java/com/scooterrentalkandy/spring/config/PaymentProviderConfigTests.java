package com.scooterrentalkandy.spring.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import com.scooterrentalkandy.spring.services.PaymentGateway;
import com.scooterrentalkandy.spring.services.StripePaymentGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Payment provider selection fails closed: no provider means no gateway at all (never a stand-in), Stripe needs
 * all three keys, and any other provider name stops startup. Only the configuration class is loaded; creating the
 * Stripe client makes no network call. The key values here are placeholders, not credentials.
 */
class PaymentProviderConfigTests {

    private static final String SECRET = "secret-value-must-not-leak";
    private static final String PUBLISHABLE = "publishable-value";
    private static final String WEBHOOK = "webhook-value-must-not-leak";

    private static ApplicationContextRunner runner(String provider, String secret, String publishable,
                                                   String webhook) {
        AppProperties props = new AppProperties(null, null, null, new AppProperties.Billing("LKR"),
                new AppProperties.Payments(provider,
                        new AppProperties.Payments.Stripe(secret, publishable, webhook)), null, null);
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> props)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("app.payments.provider=" + provider)
                .withUserConfiguration(PaymentProviderConfig.class);
    }

    @Test
    void withoutAProviderThereIsNoGatewayAndTheApplicationStarts() {
        runner("", "", "", "").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(PaymentGateway.class);
        });
        // A stray key without a provider does not enable anything either.
        runner("", SECRET, PUBLISHABLE, WEBHOOK).run(context -> assertThat(context).doesNotHaveBean(PaymentGateway.class));
    }

    @Test
    void stripeWithAllKeysIsTheOnlyGateway() {
        for (String provider : new String[] {"stripe", "Stripe"}) {
            runner(provider, SECRET, PUBLISHABLE, WEBHOOK).run(context -> {
                assertThat(context).hasSingleBean(PaymentGateway.class);
                PaymentGateway gateway = context.getBean(PaymentGateway.class);
                assertThat(gateway).isInstanceOf(StripePaymentGateway.class);
                assertThat(gateway.name()).isEqualTo("stripe");
                assertThat(gateway.publishableKey()).isEqualTo(PUBLISHABLE);
            });
        }
    }

    @Test
    void stripeWithAMissingKeyStopsStartupNamingItWithoutPrintingValues() {
        runner("stripe", "", PUBLISHABLE, WEBHOOK).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("STRIPE_SECRET_KEY is not set")
                    .hasMessageNotContaining(WEBHOOK).hasMessageNotContaining(PUBLISHABLE);
        });
        runner("stripe", SECRET, " ", null).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("STRIPE_PUBLISHABLE_KEY, STRIPE_WEBHOOK_SECRET are not set")
                    .hasMessageNotContaining(SECRET);
        });
        runner("stripe", null, null, null).run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY, STRIPE_WEBHOOK_SECRET"));
    }

    @Test
    void anyOtherProviderStopsStartup() {
        for (String provider : new String[] {"mock", "test", "paypal"}) {
            runner(provider, SECRET, PUBLISHABLE, WEBHOOK).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("Unsupported payment provider '" + provider + "'")
                        .hasMessageNotContaining(SECRET);
            });
        }
    }
}
