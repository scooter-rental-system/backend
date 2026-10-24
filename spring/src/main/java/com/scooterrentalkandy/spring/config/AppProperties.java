package com.scooterrentalkandy.spring.config;


import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Seed seed, Billing billing, Payments payments, Gps gps,
                            Notifications notifications) {

    public record Jwt(String secret, long expirationMinutes) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Seed(boolean enabled) {
    }

    public record Billing(String currency) {
    }

    /**
     * {@code provider}: the payment provider, from PAYMENTS_PROVIDER; "stripe" is the only one. Empty means no
     * provider: payment operations answer 503, nothing is simulated. {@code stripe}: the Stripe keys, from the
     * environment only (STRIPE_SECRET_KEY, STRIPE_PUBLISHABLE_KEY, STRIPE_WEBHOOK_SECRET); never committed.
     */
    public record Payments(String provider, Stripe stripe) {

        public record Stripe(String secretKey, String publishableKey, String webhookSecret) {
        }
    }

    /**
     * {@code deviceApiKey}: the key GPS devices send as X-Device-Key, from the environment; with none configured,
     * device reports are refused. {@code retentionMonths}: how long GPS logs are kept (SDS 8.3: 6 months).
     */
    public record Gps(String deviceApiKey, int retentionMonths) {
    }

    /**
     * External notification channels; in-app notifications need no configuration. {@code email.provider}: from
     * NOTIFICATIONS_EMAIL_PROVIDER. Empty means email is off: nothing is sent and nothing is recorded as sent.
     * There is no SMS provider.
     */
    public record Notifications(Email email) {

        public record Email(String provider) {
        }
    }
}
