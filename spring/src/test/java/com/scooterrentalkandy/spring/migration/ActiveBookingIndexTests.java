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
 * SDS 2.3: only one ACTIVE booking per scooter, and since V22 one reservation: a scooter holds at most one booking
 * that is READY_FOR_PICKUP or ACTIVE, enforced by a partial unique index. Each test rolls back.
 */
@Transactional
class ActiveBookingIndexTests extends PostgresIntegrationTest {

    private static final String INDEX = "ux_bookings_one_reservation_per_scooter";

    @Autowired
    private DataSource dataSource;

    @Test
    void indexExists() {
        assertThat(jdbc().queryForObject("SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, INDEX))
                .contains("UNIQUE").contains("(scooter_id)").contains("'READY_FOR_PICKUP'").contains("'ACTIVE'");
        assertThat(jdbc().queryForObject("SELECT count(*) FROM pg_indexes "
                + "WHERE indexname = 'ux_bookings_one_active_per_scooter'", Integer.class)).isZero();
    }

    @Test
    void rejectsASecondActiveBookingForTheSameScooter() {
        UUID user = insertUser();
        UUID scooter = insertScooter();
        insertBooking(user, scooter, "ACTIVE");

        assertThatThrownBy(() -> insertBooking(user, scooter, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void aBookingReadyForPickupReservesTheScooterLikeAnActiveRental() {
        UUID user = insertUser();
        UUID waiting = insertScooter();
        insertBooking(user, waiting, "READY_FOR_PICKUP");

        assertThatThrownBy(() -> insertBooking(user, waiting, "READY_FOR_PICKUP"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void aScooterOutOnARentalCannotAlsoBeReadyForPickup() {
        UUID user = insertUser();
        UUID riding = insertScooter();
        insertBooking(user, riding, "ACTIVE");

        assertThatThrownBy(() -> insertBooking(user, riding, "READY_FOR_PICKUP"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void allowsOtherStatusesAlongsideTheReservation() {
        UUID user = insertUser();
        UUID scooter = insertScooter();
        insertBooking(user, scooter, "ACTIVE");
        insertBooking(user, scooter, "CANCELLED");
        insertBooking(user, scooter, "PENDING");

        assertThat(jdbc().queryForObject("SELECT count(*) FROM bookings WHERE scooter_id = ?", Integer.class, scooter))
                .isEqualTo(3);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO users (user_id, email, password_hash, full_name, role, nic_number, created_at) "
                + "VALUES (?, ?, 'not-a-real-hash', 'Index Test', 'USER', ?, now())",
                id, id + "@example.test", id.toString().substring(0, 20));
        return id;
    }

    private UUID insertScooter() {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO scooters (scooter_id, code, model, plate_number, status, daily_rate, hourly_rate, "
                + "per_km_rate, created_at) VALUES (?, ?, 'Test model', ?, 'AVAILABLE', 2500, 250, 15, now())",
                id, "T-" + id.toString().substring(0, 8), "P-" + id.toString().substring(0, 8));
        return id;
    }

    /** An ACTIVE row has its actual start (ck_bookings_actual_start). */
    private void insertBooking(UUID user, UUID scooter, String status) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO bookings (booking_id, reference, user_id, scooter_id, status, start_time, "
                + "actual_start_time, pickup_location, created_at) VALUES (?, ?, ?, ?, ?, now(), "
                + "CASE WHEN ? = 'ACTIVE' THEN now() END, 'Test pickup', now())",
                id, "R-" + id.toString().substring(0, 8), user, scooter, status, status);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
