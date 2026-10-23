package com.scooterrentalkandy.spring.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need the database: the whole application runs against the embedded PostgreSQL 16
 * test database. Dynamic properties take precedence over environment variables, system properties and every
 * configuration file, and {@link TestDatabaseGuard} rejects any other target.
 */
@SpringBootTest
@ActiveProfiles("test")
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
