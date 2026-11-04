package com.scooterrentalkandy.spring.support;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/** Fails the suite if the guard is not registered, or if the database beans are not the embedded database. */
class TestDatabaseGuardRegistrationTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext context;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Flyway flyway;

    @Test
    void guardRanInThisContext() {
        assertThat(context.containsBean(TestDatabaseGuard.MARKER_BEAN)).isTrue();
    }

    @Test
    void dataSourceAndFlywayUseTheEmbeddedDatabase() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getJdbcUrl()).isEqualTo(EmbeddedPostgresTestDatabase.jdbcUrl());
        DataSource flywayDataSource = flyway.getConfiguration().getDataSource();
        assertThat(flywayDataSource).isInstanceOf(AbstractDriverBasedDataSource.class);
        assertThat(((AbstractDriverBasedDataSource) flywayDataSource).getUrl())
                .isEqualTo(EmbeddedPostgresTestDatabase.jdbcUrl());
    }
}
