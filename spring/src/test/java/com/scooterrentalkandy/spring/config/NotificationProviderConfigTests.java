package com.scooterrentalkandy.spring.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import com.scooterrentalkandy.spring.services.EmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Email provider selection fails closed: no provider means no sender at all (never a stand-in), and an unsupported
 * provider name, including "mock", stops startup. Only the configuration class is loaded.
 */
class NotificationProviderConfigTests {

    private static ApplicationContextRunner runner(String provider) {
        AppProperties props = new AppProperties(null, null, null, null, null, null,
                new AppProperties.Notifications(new AppProperties.Notifications.Email(provider)));
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> props)
                .withUserConfiguration(NotificationProviderConfig.class);
    }

    @Test
    void withoutAProviderTheApplicationStartsWithEmailOff() {
        for (String provider : new String[] {"", " ", null}) {
            runner(provider).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(EmailSender.class);
            });
        }
        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> new AppProperties(null, null, null, null, null, null, null))
                .withUserConfiguration(NotificationProviderConfig.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmailSender.class));
    }

    @Test
    void anUnsupportedProviderStopsStartup() {
        for (String provider : new String[] {"mock", "MOCK", "log", "smtp"}) {
            runner(provider).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("Unsupported email provider '" + provider + "'")
                        .hasMessageContaining("NOTIFICATIONS_EMAIL_PROVIDER");
            });
        }
    }

    @Test
    void theApplicationConfigurationHasNoDefaultProvider() throws Exception {
        Properties app = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(app.getProperty("app.notifications.email.provider")).isEqualTo("${NOTIFICATIONS_EMAIL_PROVIDER:}");
        assertThat(app.stringPropertyNames()).noneMatch(name -> name.contains("sms"));
    }
}
