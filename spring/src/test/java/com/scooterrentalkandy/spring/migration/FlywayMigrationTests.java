package com.scooterrentalkandy.spring.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Flyway builds the schema from empty with the configured locations, and Hibernate validates it (the context
 * would not start otherwise). V9 lives in {@code vendor/}, which is not a configured location, so it is not run;
 * V10 recreates the same partial index.
 */
class FlywayMigrationTests extends PostgresIntegrationTest {

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Test
    void appliesTheConfiguredMigrationsInOrder() {
        MigrationInfo[] applied = flyway.info().applied();
        assertThat(Arrays.stream(applied).map(m -> m.getVersion().getVersion()))
                .containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
        assertThat(applied).allSatisfy(m -> assertThat(m.getState()).isEqualTo(MigrationState.SUCCESS));
        assertThat(flyway.info().pending()).isEmpty();
    }

    @Test
    void createsEveryEntityTable() {
        List<String> tables = new JdbcTemplate(dataSource).queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'", String.class);
        assertThat(tables).contains("users", "scooters", "gear_items", "bookings", "booking_gear", "contracts",
                "payments", "payment_transactions", "invoices", "invoice_lines", "notifications",
                "maintenance_records", "gps_pings", "speed_violations", "payment_provider_events");
    }
}
