package com.scooterrentalkandy.spring.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import com.scooterrentalkandy.spring.Application;
import com.scooterrentalkandy.spring.support.TestDatabaseGuard.TestDatabaseGuardException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * Starts the real application with unsafe database settings and checks that {@link TestDatabaseGuard} stops it
 * before anything connects. Every forbidden URL uses port 1, where nothing listens, so even a guard failure could
 * not reach a real database; a connection attempt would surface as an {@link SQLException} instead.
 */
class TestDatabaseGuardBlocksTests {

    private static final String UNREACHABLE_DEV_DB = "jdbc:postgresql://127.0.0.1:1/scooter_rent_db";
    private static final String UNREACHABLE_REFERENCE_DB = "jdbc:postgresql://127.0.0.1:1/scooter_rent_kandy";
    private static final String UNREACHABLE_OTHER_DB = "jdbc:postgresql://127.0.0.1:1/some_other_db";

    @Test
    void blocksTheDevelopmentDatabaseBeforeConnecting() {
        assertBlockedBeforeConnecting(start("--spring.datasource.url=" + UNREACHABLE_DEV_DB),
                "scooter_rent_db");
    }

    @Test
    void blocksAContextWithoutTheTestProfile() {
        assertBlockedBeforeConnecting(start("--spring.profiles.active=dev"), "profile is not active");
    }

    @Test
    void blocksAContextThatWasNotGivenTheEmbeddedDatabase() {
        // Only the fail-closed placeholder from config/application.properties is configured.
        assertBlockedBeforeConnecting(start(), "PostgresIntegrationTest");
    }

    @Test
    void blocksAFlywayUrlOutsideTheEmbeddedDatabase() {
        assertBlockedBeforeConnecting(start(
                "--spring.datasource.url=" + EmbeddedPostgresTestDatabase.jdbcUrl(),
                "--spring.flyway.url=" + UNREACHABLE_REFERENCE_DB), "spring.flyway.url");
    }

    @Test
    void blocksAHikariUrlOutsideTheEmbeddedDatabase() {
        assertBlockedBeforeConnecting(start(
                "--spring.datasource.url=" + EmbeddedPostgresTestDatabase.jdbcUrl(),
                "--spring.datasource.hikari.jdbc-url=" + UNREACHABLE_OTHER_DB), "spring.datasource.hikari.jdbc-url");
    }

    private static Runnable start(String... args) {
        return () -> new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE)
                .run(withQuietBanner(args))
                .close();
    }

    private static String[] withQuietBanner(String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.add("--spring.main.banner-mode=off");
        return all.toArray(String[]::new);
    }

    private static void assertBlockedBeforeConnecting(Runnable startup, String expectedReason) {
        assertThatThrownBy(startup::run).satisfies(thrown -> {
            List<Throwable> chain = causes(thrown);
            assertThat(chain).as("the guard stopped startup")
                    .anySatisfy(t -> assertThat(t).isInstanceOf(TestDatabaseGuardException.class)
                            .hasMessageContaining(expectedReason));
            assertThat(chain).as("no connection was attempted").noneMatch(t -> t instanceof SQLException);
        });
    }

    private static List<Throwable> causes(Throwable thrown) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = thrown; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }
}
