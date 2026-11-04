package com.scooterrentalkandy.spring.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
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
 * V22 on a database that already has bookings, as a local database at V21 does: existing ACTIVE and COMPLETED rentals
 * keep billing from their start time (actual_start_time = start_time), no status changes, nothing is deleted, and the
 * new constraints and the one-reservation-per-scooter index hold. Runs on its own temporary embedded PostgreSQL
 * instance with the project's real migration locations.
 */
class V22RentalHandoverMigrationTests {

    private static final LocalDateTime START = LocalDateTime.of(2026, 9, 1, 9, 0);

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
    void backfillsTheActualStartOfStartedRentalsOnlyAndEnforcesTheNewRules() {
        DataSource dataSource = postgres.getPostgresDatabase();
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        flyway(dataSource, "21").migrate();
        UUID user = user(jdbc, "USER");
        UUID admin = user(jdbc, "ADMIN");
        UUID active = booking(jdbc, user, scooter(jdbc), "ACTIVE");
        UUID completed = booking(jdbc, user, scooter(jdbc), "COMPLETED");
        UUID pending = booking(jdbc, user, scooter(jdbc), "PENDING");
        UUID cancelled = booking(jdbc, user, scooter(jdbc), "CANCELLED");
        long before = jdbc.queryForObject("SELECT count(*) FROM bookings", Long.class);

        flyway(dataSource, "latest").migrate();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Long.class)).isEqualTo(before);
        for (UUID started : new UUID[] {active, completed}) {
            Map<String, Object> row = row(jdbc, started);
            assertThat(row.get("actual_start_time")).isEqualTo(Timestamp.valueOf(START));
            assertThat(row.get("start_time")).isEqualTo(Timestamp.valueOf(START));
        }
        for (UUID notStarted : new UUID[] {pending, cancelled}) {
            assertThat(row(jdbc, notStarted).get("actual_start_time")).isNull();
        }
        assertThat(row(jdbc, active).get("status")).isEqualTo("ACTIVE");
        assertThat(row(jdbc, completed).get("status")).isEqualTo("COMPLETED");
        assertThat(row(jdbc, pending).get("status")).isEqualTo("PENDING");
        assertThat(row(jdbc, cancelled).get("status")).isEqualTo("CANCELLED");
        assertThat(row(jdbc, completed).get("total_cost")).isNotNull();
        for (UUID any : new UUID[] {active, completed, pending, cancelled}) {
            Map<String, Object> row = row(jdbc, any);
            assertThat(row.get("start_odometer_km")).isNull();
            assertThat(row.get("end_odometer_km")).isNull();
            assertThat(row.get("handover_confirmed_at")).isNull();
        }

        // The new status, and the pickup step's rules.
        UUID ready = booking(jdbc, user, scooter(jdbc), "READY_FOR_PICKUP");
        assertThat(row(jdbc, ready).get("status")).isEqualTo("READY_FOR_PICKUP");
        rejected(jdbc, "ck_bookings_actual_start",
                "UPDATE bookings SET status = 'ACTIVE' WHERE booking_id = ?", ready);
        rejected(jdbc, "ck_bookings_handover",
                "UPDATE bookings SET start_odometer_km = 10 WHERE booking_id = ?", ready);
        rejected(jdbc, "ck_bookings_start_odometer", "UPDATE bookings SET handover_confirmed_at = now(), "
                + "handover_confirmed_by = '" + admin + "', start_odometer_km = -1 WHERE booking_id = ?", ready);
        rejected(jdbc, "ck_bookings_customer_start",
                "UPDATE bookings SET customer_start_confirmed_at = now() WHERE booking_id = ?", ready);
        jdbc.update("UPDATE bookings SET handover_confirmed_at = now(), handover_confirmed_by = ?, "
                + "start_odometer_km = 1500.5 WHERE booking_id = ?", admin, ready);
        rejected(jdbc, "ck_bookings_end_odometer",
                "UPDATE bookings SET end_odometer_km = 1500.4 WHERE booking_id = ?", ready);
        rejected(jdbc, "ck_bookings_end_odometer",
                "UPDATE bookings SET end_odometer_km = 10 WHERE booking_id = ?", active); // no starting reading
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint "
                + "WHERE conname = 'ck_bookings_status'", String.class))
                .contains("'PENDING'", "'READY_FOR_PICKUP'", "'ACTIVE'", "'COMPLETED'", "'CANCELLED'");

        // One reservation per scooter: READY_FOR_PICKUP and ACTIVE both hold it.
        UUID scooterOfActive = jdbc.queryForObject("SELECT scooter_id FROM bookings WHERE booking_id = ?", UUID.class,
                active);
        assertThatThrownBy(() -> booking(jdbc, user, scooterOfActive, "READY_FOR_PICKUP"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_bookings_one_reservation_per_scooter");
    }

    private static void rejected(JdbcTemplate jdbc, String constraint, String sql, UUID id) {
        assertThatThrownBy(() -> jdbc.update(sql, id))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(constraint);
    }

    private static Map<String, Object> row(JdbcTemplate jdbc, UUID id) {
        return jdbc.queryForMap("SELECT * FROM bookings WHERE booking_id = ?", id);
    }

    private static UUID user(JdbcTemplate jdbc, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (user_id, email, password_hash, full_name, role, nic_number, created_at) "
                + "VALUES (?, ?, 'not-a-real-hash', 'Migration Test', ?, ?, now())",
                id, id + "@example.test", role, id.toString().substring(0, 20));
        return id;
    }

    private static UUID scooter(JdbcTemplate jdbc) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO scooters (scooter_id, code, model, plate_number, status, daily_rate, hourly_rate, "
                + "per_km_rate, created_at) VALUES (?, ?, 'Test model', ?, 'AVAILABLE', 2500, 250, 15, now())",
                id, "V22-" + id.toString().substring(0, 8), "P-" + id.toString().substring(0, 8));
        return id;
    }

    /** A booking as rows were at V21: a COMPLETED one carries its final fields (ck_bookings_final_fields). */
    private static UUID booking(JdbcTemplate jdbc, UUID user, UUID scooter, String status) {
        UUID id = UUID.randomUUID();
        boolean done = status.equals("COMPLETED");
        jdbc.update("INSERT INTO bookings (booking_id, reference, user_id, scooter_id, status, start_time, end_time, "
                + "distance_km, total_cost, pickup_location, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, "
                + "'Test pickup', now())",
                id, "V22-" + id.toString().substring(0, 8), user, scooter, status, Timestamp.valueOf(START),
                done ? Timestamp.valueOf(START.plusHours(3)) : null, done ? 12.5 : null, done ? 750 : null);
        return id;
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
