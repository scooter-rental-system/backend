package com.scooterrentalkandy.spring.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Characterisation of a known limitation, without changing the migration: V11 cannot upgrade a V10 database that
 * already has users. V11:23 fills {@code nic_number varchar(20)} with {@code 'TEMP-' || user_id::text}, which is
 * 41 characters, so PostgreSQL rejects it (SQLState 22001) and the whole migration rolls back. The expected
 * failure is asserted, so this test passes while the limitation exists.
 *
 * <p>Runs on its own temporary embedded PostgreSQL instance, with no Spring context, using the project's real
 * migration locations.
 */
class V11PopulatedUsersMigrationTests {

    private static EmbeddedPostgres postgres;

    @BeforeAll
    static void startOwnDatabase() throws IOException {
        postgres = EmbeddedPostgres.builder().start();
    }

    @AfterAll
    static void stopOwnDatabase() throws IOException {
        if (postgres != null) {
            postgres.close();
        }
    }

    @Test
    void v11FailsAndRollsBackOnAV10DatabaseWithUsers() {
        DataSource dataSource = postgres.getPostgresDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "10").migrate();
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, full_name, role, created_at) "
                + "VALUES (?, 'existing.user@example.test', 'not-a-real-hash', 'Existing User', 'USER', now())", userId);

        Flyway toLatest = flyway(dataSource, "latest");
        assertThatThrownBy(toLatest::migrate)
                .isInstanceOf(FlywayException.class)
                .satisfies(thrown -> assertThat(isValueTooLong(thrown)).as("fails with SQLState 22001").isTrue());

        assertThat(toLatest.info().current().getVersion().getVersion()).isEqualTo("10");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE version = '11' AND success", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("SELECT id, email FROM users")).singleElement().satisfies(row -> {
            assertThat(row.get("id")).isEqualTo(userId);
            assertThat(row.get("email")).isEqualTo("existing.user@example.test");
        });
        List<String> columns = jdbc.queryForList("SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'users'", String.class);
        assertThat(columns).contains("id").doesNotContain("user_id", "nic_number", "is_active");
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:migration", "classpath:db/migration")
                .cleanDisabled(true)
                .target(target)
                .load();
    }

    private static boolean isValueTooLong(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && "22001".equals(sql.getSQLState())) {
                return true;
            }
            if (t.getMessage() != null && t.getMessage().contains("22001")) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
