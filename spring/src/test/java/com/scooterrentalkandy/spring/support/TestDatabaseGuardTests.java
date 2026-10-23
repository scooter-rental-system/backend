package com.scooterrentalkandy.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.scooterrentalkandy.spring.support.TestDatabaseGuard.TestDatabaseGuardException;
import org.junit.jupiter.api.Test;

/** The guard's decisions, without Spring and without any database. */
class TestDatabaseGuardTests {

    private static final String EMBEDDED = "jdbc:postgresql://localhost:54321/scooter_rental_test";

    @Test
    void acceptsOnlyTheExactEmbeddedUrl() {
        assertThatCode(() -> TestDatabaseGuard.checkUrl("test", EMBEDDED, EMBEDDED)).doesNotThrowAnyException();
    }

    @Test
    void rejectsTheRealDevelopmentDatabases() {
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test",
                "jdbc:postgresql://localhost:5432/scooter_rent_db", EMBEDDED))
                .isInstanceOf(TestDatabaseGuardException.class).hasMessageContaining("scooter_rent_db");
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test",
                "jdbc:postgresql://localhost:5432/SCOOTER_RENT_KANDY", EMBEDDED))
                .isInstanceOf(TestDatabaseGuardException.class).hasMessageContaining("real database");
    }

    @Test
    void rejectsAnyOtherHostPortOrDatabase() {
        for (String url : new String[] {
                "jdbc:postgresql://db.example.com:5432/scooter_rental_test",
                "jdbc:postgresql://localhost:5432/scooter_rental_test",
                "jdbc:postgresql://localhost:54321/postgres",
                EMBEDDED + "?currentSchema=other",
                "jdbc:h2:mem:srk"}) {
            assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test", url, EMBEDDED))
                    .as(url).isInstanceOf(TestDatabaseGuardException.class);
        }
    }

    @Test
    void rejectsThePlaceholderAndEverythingElseWhenNoEmbeddedDatabaseIsRunning() {
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test",
                "jdbc:postgresql://127.0.0.1:1/embedded_test_db_not_started", null))
                .isInstanceOf(TestDatabaseGuardException.class).hasMessageContaining("PostgresIntegrationTest");
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test", EMBEDDED, null))
                .isInstanceOf(TestDatabaseGuardException.class);
    }

    @Test
    void messagesNeverContainCredentials() {
        assertThatThrownBy(() -> TestDatabaseGuard.checkUrl("test",
                "jdbc:postgresql://someone:secret-value@localhost:5432/scooter_rent_db?password=secret-value",
                EMBEDDED))
                .isInstanceOf(TestDatabaseGuardException.class)
                .hasMessageNotContaining("secret-value").hasMessageNotContaining("someone");
    }

    @Test
    void requiresTheTestProfile() {
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestProfile(new String[0]))
                .isInstanceOf(TestDatabaseGuardException.class);
        assertThatThrownBy(() -> TestDatabaseGuard.requireTestProfile(new String[] {"dev"}))
                .isInstanceOf(TestDatabaseGuardException.class);
        assertThatCode(() -> TestDatabaseGuard.requireTestProfile(new String[] {"test"}))
                .doesNotThrowAnyException();
    }

    @Test
    void theTestJvmInheritsNoDatabaseEnvironmentVariables() {
        assertThat(System.getenv().keySet()).noneMatch(name -> name.startsWith("SPRING_DATASOURCE")
                || name.startsWith("SPRING_FLYWAY") || name.startsWith("PG") || name.equals("DATABASE_URL"));
    }
}
