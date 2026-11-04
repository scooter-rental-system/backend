package com.scooterrentalkandy.spring.support;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.datasource.AbstractDriverBasedDataSource;

/**
 * Keeps every Spring context started by the tests away from real databases. Registered for all tests through
 * {@code META-INF/spring.factories} (test resources only), it checks in three stages, all before any connection:
 * <ol>
 *   <li>as an initializer: the {@code test} profile must be active;</li>
 *   <li>as a bean factory post-processor, after every initializer has contributed its properties (including
 *       {@code @DynamicPropertySource}) and before any bean exists: every configured database URL must be the
 *       embedded test database;</li>
 *   <li>as a bean post-processor: the URL that each {@link DataSource} and {@link Flyway} bean will actually use
 *       must be the embedded test database. Flyway migrates and Hibernate connects only after these beans are
 *       initialised, so a rejected bean never opens a connection.</li>
 * </ol>
 * Messages name host, port and database only, never credentials.
 */
public class TestDatabaseGuard implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    public static final String MARKER_BEAN = "testDatabaseGuardMarker";

    static final String TEST_PROFILE = "test";
    static final Set<String> FORBIDDEN_DATABASES = Set.of("scooter_rent_db", "scooter_rent_kandy");
    static final List<String> URL_PROPERTIES = List.of(
            "spring.datasource.url", "spring.datasource.hikari.jdbc-url", "spring.flyway.url");

    /** Thrown when a test context would reach anything but the embedded test database. */
    public static final class TestDatabaseGuardException extends IllegalStateException {
        TestDatabaseGuardException(String message) {
            super("TestDatabaseGuard: " + message);
        }
    }

    /** Lets tests assert that the guard ran in their context. */
    public static final class Marker {
    }

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment environment = context.getEnvironment();
        requireTestProfile(environment.getActiveProfiles());
        context.addBeanFactoryPostProcessor(beanFactory -> {
            if (usesDatabase(beanFactory)) {
                for (String property : URL_PROPERTIES) {
                    String url = environment.getProperty(property);
                    if (url != null) {
                        checkUrl("property " + property, url, embeddedUrlIfStarted());
                    }
                }
            }
            beanFactory.addBeanPostProcessor(new ConnectionTargetCheck());
            beanFactory.registerSingleton(MARKER_BEAN, new Marker());
        });
    }

    static void requireTestProfile(String[] activeProfiles) {
        if (!Arrays.asList(activeProfiles).contains(TEST_PROFILE)) {
            throw new TestDatabaseGuardException("the '" + TEST_PROFILE + "' profile is not active (active: "
                    + Arrays.toString(activeProfiles) + "); refusing to start a test context");
        }
    }

    /** Accepts {@code url} only if it is exactly the embedded test database's URL. */
    static void checkUrl(String source, String url, String embeddedUrl) {
        String database = databaseName(url);
        if (database != null && FORBIDDEN_DATABASES.contains(database.toLowerCase())) {
            throw new TestDatabaseGuardException(source + " points to the real database '" + database
                    + "' (" + describe(url) + "); tests may only use the embedded test database");
        }
        if (embeddedUrl == null || !embeddedUrl.equals(url)) {
            throw new TestDatabaseGuardException(source + " is " + describe(url)
                    + ", not the embedded test database; database tests must extend PostgresIntegrationTest");
        }
    }

    static void checkDataSource(String source, DataSource dataSource) {
        String url;
        if (dataSource instanceof HikariDataSource hikari) {
            url = hikari.getJdbcUrl();
        } else if (dataSource instanceof AbstractDriverBasedDataSource driverBased) {
            url = driverBased.getUrl();
        } else {
            throw new TestDatabaseGuardException(source + " is a " + (dataSource == null ? "null DataSource"
                    : dataSource.getClass().getName()) + " whose URL cannot be verified");
        }
        if (url == null) {
            throw new TestDatabaseGuardException(source + " has no URL to verify");
        }
        checkUrl(source, url, embeddedUrlIfStarted());
    }

    /** {@code host:port/database} of a JDBC URL, without user info or parameters. */
    static String describe(String url) {
        int scheme = url.indexOf("://");
        if (!url.startsWith("jdbc:") || scheme < 0) {
            int end = url.indexOf(':', "jdbc:".length());
            return (end > 0 ? url.substring(0, end) : "jdbc") + " URL (not PostgreSQL)";
        }
        String rest = url.substring(scheme + 3);
        int query = rest.indexOf('?');
        if (query >= 0) {
            rest = rest.substring(0, query);
        }
        int at = rest.lastIndexOf('@');
        return at >= 0 ? rest.substring(at + 1) : rest;
    }

    static String databaseName(String url) {
        if (!url.contains("://")) {
            return null;
        }
        String target = describe(url);
        int slash = target.indexOf('/');
        return slash >= 0 ? target.substring(slash + 1) : null;
    }

    private static boolean usesDatabase(ConfigurableListableBeanFactory beanFactory) {
        return beanFactory.getBeanNamesForType(DataSource.class, true, false).length > 0
                || beanFactory.getBeanNamesForType(Flyway.class, true, false).length > 0;
    }

    private static String embeddedUrlIfStarted() {
        return EmbeddedPostgresTestDatabase.isStarted() ? EmbeddedPostgresTestDatabase.jdbcUrl() : null;
    }

    private static final class ConnectionTargetCheck implements BeanPostProcessor {

        @Override
        public Object postProcessAfterInitialization(Object bean, String beanName) {
            if (bean instanceof DataSource dataSource) {
                checkDataSource("DataSource bean '" + beanName + "'", dataSource);
            }
            if (bean instanceof Flyway flyway) {
                checkDataSource("Flyway bean '" + beanName + "'", flyway.getConfiguration().getDataSource());
            }
            return bean;
        }
    }
}
