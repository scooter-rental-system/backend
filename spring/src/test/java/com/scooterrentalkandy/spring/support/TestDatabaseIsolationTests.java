package com.scooterrentalkandy.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database the application actually talks to in tests is the temporary embedded PostgreSQL 16.15. */
class TestDatabaseIsolationTests extends PostgresIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void connectsToTheEmbeddedTestDatabaseOnly() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(EmbeddedPostgresTestDatabase.DATABASE)
                .isNotIn(TestDatabaseGuard.FORBIDDEN_DATABASES);
        assertThat(jdbc.queryForObject("SELECT inet_server_port()", Integer.class))
                .isEqualTo(EmbeddedPostgresTestDatabase.port());
        assertThat(EmbeddedPostgresTestDatabase.jdbcUrl()).startsWith("jdbc:postgresql://localhost:");
    }

    @Test
    void runsPostgreSql16WithUtf8() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        assertThat(jdbc.queryForObject("SHOW server_version", String.class)).startsWith("16.15");
        assertThat(jdbc.queryForObject("SHOW server_encoding", String.class)).isEqualTo("UTF8");
    }
}
