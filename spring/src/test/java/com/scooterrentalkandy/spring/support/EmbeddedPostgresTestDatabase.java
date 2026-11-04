package com.scooterrentalkandy.spring.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;

/**
 * The one PostgreSQL 16 instance shared by the integration tests of a test JVM: started on first use on a free
 * localhost port with a temporary data directory, and stopped when the JVM exits. Tests use only the
 * {@value #DATABASE} database inside it.
 */
public final class EmbeddedPostgresTestDatabase {

    public static final String DATABASE = "scooter_rental_test";
    public static final String USER = "postgres";
    public static final String PASSWORD = "";

    private static EmbeddedPostgres postgres;

    private EmbeddedPostgresTestDatabase() {
    }

    public static synchronized boolean isStarted() {
        return postgres != null;
    }

    public static synchronized int port() {
        return instance().getPort();
    }

    public static String jdbcUrl() {
        return "jdbc:postgresql://localhost:" + port() + "/" + DATABASE;
    }

    private static synchronized EmbeddedPostgres instance() {
        if (postgres == null) {
            EmbeddedPostgres started = start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    started.close();
                } catch (IOException ignored) {
                    // the JVM is exiting; the temporary data directory is removed with the instance
                }
            }));
            postgres = started;
        }
        return postgres;
    }

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres started = EmbeddedPostgres.builder().start();
            try (Connection c = started.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
                s.execute("CREATE DATABASE " + DATABASE);
            }
            return started;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not start the embedded PostgreSQL test database", e);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not create the " + DATABASE + " test database", e);
        }
    }
}
