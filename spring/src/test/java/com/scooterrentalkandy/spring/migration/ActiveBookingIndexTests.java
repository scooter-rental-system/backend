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

/** SDS 2.3: only one ACTIVE booking per scooter, enforced by a partial unique index. Each test rolls back. */
@Transactional
class ActiveBookingIndexTests extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void indexExists() {
        assertThat(jdbc().queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE indexname = 'ux_bookings_one_active_per_scooter'",
                String.class)).contains("UNIQUE").contains("(scooter_id)").contains("'ACTIVE'");
    }

    @Test
    void rejectsASecondActiveBookingForTheSameScooter() {
        UUID user = insertUser();
        UUID scooter = insertScooter();
        insertBooking(user, scooter, "ACTIVE");

        assertThatThrownBy(() -> insertBooking(user, scooter, "ACTIVE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ux_bookings_one_active_per_scooter");
    }

    @Test
    void allowsOtherStatusesAlongsideTheActiveBooking() {
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

    private void insertBooking(UUID user, UUID scooter, String status) {
        UUID id = UUID.randomUUID();
        jdbc().update("INSERT INTO bookings (booking_id, reference, user_id, scooter_id, status, start_time, "
                + "pickup_location, created_at) VALUES (?, ?, ?, ?, ?, now(), 'Test pickup', now())",
                id, "R-" + id.toString().substring(0, 8), user, scooter, status);
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
