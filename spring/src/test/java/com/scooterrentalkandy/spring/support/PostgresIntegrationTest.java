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
 * <p>The payment provider is the test-only {@link TestPaymentGateway}; no real or simulated provider is called.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(TestPaymentGateway.class)
public abstract class PostgresIntegrationTest {

    @DynamicPropertySource
    static void embeddedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", EmbeddedPostgresTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> EmbeddedPostgresTestDatabase.USER);
        registry.add("spring.datasource.password", () -> EmbeddedPostgresTestDatabase.PASSWORD);
        registry.add("spring.flyway.url", EmbeddedPostgresTestDatabase::jdbcUrl);
        registry.add("spring.flyway.user", () -> EmbeddedPostgresTestDatabase.USER);
        registry.add("spring.flyway.password", () -> EmbeddedPostgresTestDatabase.PASSWORD);
    }
}
