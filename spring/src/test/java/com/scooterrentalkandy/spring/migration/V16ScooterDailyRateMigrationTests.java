package com.scooterrentalkandy.spring.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V16 on a database that already has scooters, as a local database at V15 does: every existing scooter gets a daily
 * rate of ten times its hourly rate, the column becomes NOT NULL with a positive check, and nothing else changes.
 * Runs on its own temporary embedded PostgreSQL instance with the project's real migration locations.
 */
class V16ScooterDailyRateMigrationTests {

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
    void populatesTheDailyRateOfExistingScootersAndEnforcesIt() {
        DataSource dataSource = postgres.getPostgresDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "15").migrate();
        UUID dio = scooterAtV15(jdbc, "SRK-01", "CP BHA-1234", "250.00");
        UUID ntorq = scooterAtV15(jdbc, "SRK-02", "CP BHB-5678", "320.00");
        UUID vespa = scooterAtV15(jdbc, "SRK-06", "CP BHF-2468", "450.00");

        flyway(dataSource, "latest").migrate();

        assertThat(dailyRate(jdbc, dio)).isEqualByComparingTo("2500.00");
        assertThat(dailyRate(jdbc, ntorq)).isEqualByComparingTo("3200.00");
        assertThat(dailyRate(jdbc, vespa)).isEqualByComparingTo("4500.00");
        Map<String, Object> rates = jdbc.queryForMap("SELECT hourly_rate, per_km_rate FROM scooters WHERE scooter_id = ?",
                dio);
        assertThat((BigDecimal) rates.get("hourly_rate")).isEqualByComparingTo("250.00");
        assertThat((BigDecimal) rates.get("per_km_rate")).isEqualByComparingTo("15.00");

        assertThat(jdbc.queryForObject("SELECT is_nullable FROM information_schema.columns "
                + "WHERE table_name = 'scooters' AND column_name = 'daily_rate'", String.class)).isEqualTo("NO");
        assertThatThrownBy(() -> jdbc.update("UPDATE scooters SET daily_rate = 0 WHERE scooter_id = ?", dio))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_scooters_daily_rate");
    }

    private static UUID scooterAtV15(JdbcTemplate jdbc, String code, String plate, String hourlyRate) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO scooters (scooter_id, code, model, plate_number, status, hourly_rate, per_km_rate, "
                + "total_mileage, deleted, created_at) VALUES (?, ?, 'Demo', ?, 'AVAILABLE', ?, 15.00, 0, false, now())",
                id, code, plate, new BigDecimal(hourlyRate));
        return id;
    }

    private static BigDecimal dailyRate(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForObject("SELECT daily_rate FROM scooters WHERE scooter_id = ?", BigDecimal.class, id);
    }

    private static Flyway flyway(DataSource dataSource, String target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:migration", "classpath:db/migration")
                .cleanDisabled(true)
                .target(target)
                .load();
    }
}
