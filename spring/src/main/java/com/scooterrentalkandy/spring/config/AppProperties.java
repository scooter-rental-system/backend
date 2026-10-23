package com.scooterrentalkandy.spring.config;


import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record AppProperties(Jwt jwt, Cors cors, Seed seed, Billing billing, Payments payments, Gps gps) {

    public record Jwt(String secret, long expirationMinutes) {
    }

    public record Cors(List<String> allowedOrigins) {
    }

    public record Seed(boolean enabled) {
    }

    public record Billing(String currency) {
    }

    public record Payments(String provider, String webhookSecret) {
    }

    /**
     * {@code deviceApiKey}: the key GPS devices send as X-Device-Key, from the environment; with none configured,
     * device reports are refused. {@code retentionMonths}: how long GPS logs are kept (SDS 8.3: 6 months).
     */
    public record Gps(String deviceApiKey, int retentionMonths) {
    }
}
