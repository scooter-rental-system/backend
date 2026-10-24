package com.scooterrentalkandy.spring.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import javax.sql.DataSource;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The V12 CHECK constraints, exercised with plain SQL so that only the database (not the application's
 * validation) stands in the way. Each test makes at most one rejected insert, last, and rolls back.
 */
@Transactional
class IntegrityConstraintTests extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }

    private UUID insertGear(String dailyRate, int stock) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO gear_items (gear_item_id, name, category, daily_rate, total_quantity, active) "
                + "VALUES (?, ?, 'CAMPING', CAST(? AS NUMERIC), ?, TRUE)", id, "Gear " + id, dailyRate, stock);
        return id;
    }

    private UUID insertScooter() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO scooters (scooter_id, code, model, plate_number, status, hourly_rate, per_km_rate, "
                + "created_at) VALUES (?, ?, 'Test model', ?, 'AVAILABLE', 250, 15, now())",
                id, "IC-" + id.toString().substring(0, 8), "ICP-" + id.toString().substring(0, 8));
        return id;
    }

    private UUID insertBooking() {
        UUID user = UUID.randomUUID();
        jdbc().update("INSERT INTO users (user_id, email, password_hash, full_name, role, nic_number, created_at) "
                + "VALUES (?, ?, 'not-a-real-hash', 'Constraint Test', 'USER', ?, now())",
                user, user + "@example.test", user.toString().substring(0, 20));
        UUID booking = UUID.randomUUID();
        jdbc().update("INSERT INTO bookings (booking_id, reference, user_id, scooter_id, status, start_time, "
                + "pickup_location, created_at) VALUES (?, ?, ?, ?, 'PENDING', now(), 'Test pickup', now())",
                booking, "IC-" + booking.toString().substring(0, 8), user, insertScooter());
        return booking;
    }

    private void insertBookingGear(UUID booking, UUID gear, int quantity) {
        jdbc().update("INSERT INTO booking_gear (booking_gear_id, booking_id, gear_item_id, quantity) "
                + "VALUES (?, ?, ?, ?)", UUID.randomUUID(), booking, gear, quantity);
    }

    private void insertMaintenance(String type, String status, String cost) {
        jdbc().update("INSERT INTO maintenance_records (maintenance_record_id, scooter_id, type, status, description, "
                + "cost, created_at) VALUES (?, ?, ?, ?, 'Constraint test', CAST(? AS NUMERIC), now())",
                UUID.randomUUID(), insertScooter(), type, status, cost);
    }

    @Test
    void boundaryValuesAreAccepted() {
        UUID soldOut = insertGear("0.01", 0);
        insertBookingGear(insertBooking(), insertGear("300.00", 1), 1);
        insertMaintenance("SERVICE", "SCHEDULED", null);
        insertMaintenance("REPAIR", "IN_PROGRESS", "0");
        insertMaintenance("INSPECTION", "DONE", "1250.50");
        insertMaintenance("REPAIR", "CANCELLED", null);

        assertThat(jdbc().queryForObject("SELECT total_quantity FROM gear_items WHERE gear_item_id = ?", Integer.class,
                soldOut)).isZero();
    }

    @Test
    void negativeGearStockIsRejected() {
        assertThatThrownBy(() -> insertGear("300.00", -1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gear_items_total_quantity");
    }

    @Test
    void zeroGearRateIsRejected() {
        assertThatThrownBy(() -> insertGear("0.00", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gear_items_daily_rate");
    }

    @Test
    void negativeGearRateIsRejected() {
        assertThatThrownBy(() -> insertGear("-5.00", 1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gear_items_daily_rate");
    }

    @Test
    void zeroBookingGearQuantityIsRejected() {
        UUID booking = insertBooking();
        UUID gear = insertGear("300.00", 5);

        assertThatThrownBy(() -> insertBookingGear(booking, gear, 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_booking_gear_quantity");
    }

    @Test
    void unknownMaintenanceStatusIsRejected() {
        assertThatThrownBy(() -> insertMaintenance("REPAIR", "PAUSED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_maintenance_status");
    }

    @Test
    void unknownMaintenanceTypeIsRejected() {
        assertThatThrownBy(() -> insertMaintenance("PAINT", "SCHEDULED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_maintenance_type");
    }

    // ── V13: GPS ──────────────────────────────────────────────────────────────

    private void insertPing(UUID booking, String lat, String lng) {
        UUID scooter = jdbc().queryForObject("SELECT scooter_id FROM bookings WHERE booking_id = ?", UUID.class,
                booking);
        jdbc().update("INSERT INTO gps_pings (gps_ping_id, scooter_id, booking_id, latitude, longitude, speed_kmh, "
                + "recorded_at) VALUES (?, ?, ?, CAST(? AS NUMERIC), CAST(? AS NUMERIC), 10, now())",
                UUID.randomUUID(), scooter, booking, lat, lng);
    }

    @Test
    void gpsCoordinatesAtTheLimitsAreAccepted() {
        UUID booking = insertBooking();
        insertPing(booking, "90", "180");
        insertPing(booking, "-90", "-180");

        assertThat(jdbc().queryForObject("SELECT count(*) FROM gps_pings WHERE booking_id = ?", Integer.class,
                booking)).isEqualTo(2);
    }

    @Test
    void anImpossibleLatitudeIsRejected() {
        UUID booking = insertBooking();

        assertThatThrownBy(() -> insertPing(booking, "90.000001", "80"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gps_pings_latitude");
    }

    @Test
    void anImpossibleLongitudeIsRejected() {
        UUID booking = insertBooking();

        assertThatThrownBy(() -> insertPing(booking, "7", "-180.5"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_gps_pings_longitude");
    }

    @Test
    void theUnusedGpsLogsTableIsGone() {
        assertThat(jdbc().queryForObject("SELECT to_regclass('public.gps_logs') IS NULL", Boolean.class)).isTrue();
        assertThat(jdbc().queryForObject("SELECT to_regclass('public.gps_pings') IS NOT NULL", Boolean.class))
                .isTrue();
    }

    @Test
    void negativeMaintenanceCostIsRejected() {
        assertThatThrownBy(() -> insertMaintenance("REPAIR", "DONE", "-0.01"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_maintenance_cost");
    }
}
