package com.scooterrentalkandy.spring.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the database: the whole application runs against the embedded PostgreSQL 16
 * test database. Dynamic properties take precedence over environment variables, system properties and every
 * configuration file, and {@link TestDatabaseGuard} rejects any other target.
 *
 * <p>No payment provider is configured, whatever the environment says, so the application never creates a real
 * Stripe client; the provider is the test-only {@link TestPaymentGateway}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestPaymentGateway.class)
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    public static void embeddedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> EmbeddedPostgresTestDatabase.USER);
        registry.add("spring.datasource.password", () -> EmbeddedPostgresTestDatabase.PASSWORD);
        registry.add("spring.flyway.url", EmbeddedPostgresTestDatabase::jdbcUrl);
        registry.add("spring.flyway.user", () -> EmbeddedPostgresTestDatabase.USER);
        registry.add("spring.flyway.password", () -> EmbeddedPostgresTestDatabase.PASSWORD);
        registry.add("app.payments.provider", () -> "");
        registry.add("app.payments.stripe.secret-key", () -> "");
        registry.add("app.payments.stripe.publishable-key", () -> "");
        registry.add("app.payments.stripe.webhook-secret", () -> "");
    }
}
