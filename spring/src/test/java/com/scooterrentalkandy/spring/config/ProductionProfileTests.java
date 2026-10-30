package com.scooterrentalkandy.spring.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Properties;
import com.scooterrentalkandy.spring.services.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/** BE-035: the prod profile turns demo data and SQL logging off and takes every secret from the environment. */
class ProductionProfileTests {

    private static Properties prod() throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application-prod.properties"));
    }

    private static Properties base() throws IOException {
        return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
    }

    @Test
    void turnsOffDemoSeedingAndSqlLogging() throws IOException {
        Properties p = prod();
        assertThat(p.getProperty("app.seed.enabled")).isEqualTo("false");
        assertThat(p.getProperty("spring.jpa.show-sql")).isEqualTo("false");
        assertThat(p.getProperty("spring.jpa.properties.hibernate.format_sql")).isEqualTo("false");
    }

    @Test
    void takesTheDatabaseJwtSecretAndCorsFromTheEnvironmentOnly() throws IOException {
        Properties p = prod();
        assertThat(p.getProperty("spring.datasource.url")).isEqualTo("${SPRING_DATASOURCE_URL}");
        assertThat(p.getProperty("spring.datasource.username")).isEqualTo("${SPRING_DATASOURCE_USERNAME}");
        assertThat(p.getProperty("spring.datasource.password")).isEqualTo("${SPRING_DATASOURCE_PASSWORD}");
        assertThat(p.getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET:}");
        assertThat(p.getProperty("app.cors.allowed-origins")).isEqualTo("${CORS_ORIGINS}");
    }

    @Test
    void trustsForwardedClientAddressesOnlyThroughTheServletContainer() throws IOException {
        assertThat(prod().getProperty("server.forward-headers-strategy")).isEqualTo("native");
    }

    @Test
    void shipsNoBuiltInJwtSecret() throws IOException {
        assertThat(base().getProperty("app.jwt.secret")).isEqualTo("${JWT_SECRET:}");
    }

    @Test
    void neverSeedsDemoAccountsUnderTheProdProfile() {
        Profile profile = DataSeeder.class.getAnnotation(Profile.class);
        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactly("!prod");
    }
}
