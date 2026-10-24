package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import javax.sql.DataSource;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * The demo seeder runs to completion on the migrated schema and gives each demo user a distinct NIC. Only this
 * class turns seeding on (its DataSeeder log line is silenced); the seeded rows are removed afterwards so the shared
 * embedded test database is empty again for other tests.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestPropertySource(properties = {
        "app.seed.enabled=true",
        "logging.level.com.scooterrentalkandy.spring.services.DataSeeder=WARN"})
class DemoSeederNicTests extends PostgresIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Test
    void seedsTheThreeDemoUsersWithDistinctNics() {
        List<User> seeded = users.findAll();

        assertThat(seeded).hasSize(3);
        assertThat(seeded).extracting(User::getNicNumber)
                .containsExactlyInAnyOrder("000000000001", "000000000002", "000000000003");
    }

    @Test
    void completesTheRestOfTheDemoData() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scooters", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM gear_items", Integer.class)).isEqualTo(8);
    }

    /** A booking is paid only when the payment provider confirms it; the seeder fakes no financial history. */
    @Test
    void seedsNoBookingsOrPayments() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bookings", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payments", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM invoices", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM scooters WHERE status <> 'AVAILABLE'", Integer.class))
                .isZero();
    }

    @AfterAll
    void removeSeededData() {
        // users, scooters and gear_items are the roots; CASCADE empties every table that references them.
        new JdbcTemplate(dataSource).execute("TRUNCATE TABLE users, scooters, gear_items CASCADE");
    }
}
