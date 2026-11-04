package com.scooterrentalkandy.spring.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import com.scooterrentalkandy.spring.services.EmailSender;
import com.scooterrentalkandy.spring.services.SesEmailSender;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

/**
 * Email provider selection fails closed: no provider means no sender at all (never a stand-in), SES needs its
 * sender address and region, and any other provider name, including "mock", stops startup. Only the configuration
 * class is loaded; creating the SES client resolves no credentials and makes no network call.
 */
class NotificationProviderConfigTests {

    private static final String FROM = "no-reply@example.test";
    private static final String REGION = "ap-south-1";

    private static ApplicationContextRunner runner(String provider, String from, String region) {
        AppProperties props = new AppProperties(null, null, null, null, null, null,
                new AppProperties.Notifications(new AppProperties.Notifications.Email(provider, from, region)));
        return new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> props)
                .withPropertyValues("app.notifications.email.provider=" + (provider == null ? "" : provider))
                .withUserConfiguration(NotificationProviderConfig.class);
    }

    @Test
    void withoutAProviderTheApplicationStartsWithEmailOff() {
        for (String provider : new String[] {"", " ", null}) {
            runner(provider, null, null).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(EmailSender.class);
            });
        }
        // Stray SES settings without a provider do not enable anything.
        runner("", FROM, REGION).run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmailSender.class));
        new ApplicationContextRunner()
                .withBean(AppProperties.class, () -> new AppProperties(null, null, null, null, null, null, null))
                .withUserConfiguration(NotificationProviderConfig.class)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(EmailSender.class));
    }

    @Test
    void sesWithItsSettingsIsTheOnlySender() {
        for (String provider : new String[] {"ses", "SES"}) {
            runner(provider, " " + FROM + " ", REGION).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(EmailSender.class);
                assertThat(context.getBean(EmailSender.class)).isInstanceOf(SesEmailSender.class);
            });
        }
    }

    @Test
    void sesWithoutItsSettingsStopsStartupNamingThem() {
        runner("ses", "", REGION).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("NOTIFICATIONS_EMAIL_FROM is not set")
                    .hasMessageNotContaining("AWS_REGION");
        });
        runner("ses", FROM, " ").run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("AWS_REGION is not set")
                .hasMessageNotContaining("NOTIFICATIONS_EMAIL_FROM"));
        runner("ses", null, null).run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("NOTIFICATIONS_EMAIL_FROM is not set and AWS_REGION is not set"));
    }

    @Test
    void sesWithMalformedSettingsStopsStartup() {
        runner("ses", "not-an-address", REGION).run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("NOTIFICATIONS_EMAIL_FROM is not an email address"));
        runner("ses", FROM, "Mumbai").run(context -> assertThat(context.getStartupFailure()).rootCause()
                .hasMessageContaining("AWS_REGION is not an AWS region name"));
    }

    @Test
    void anUnsupportedProviderStopsStartup() {
        for (String provider : new String[] {"mock", "MOCK", "log", "smtp", "sns"}) {
            runner(provider, FROM, REGION).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).rootCause()
                        .hasMessageContaining("Unsupported email provider '" + provider + "'")
                        .hasMessageContaining("NOTIFICATIONS_EMAIL_PROVIDER");
            });
        }
    }

    @Test
    void theApplicationConfigurationHasNoDefaultProviderAndNoCredentials() throws Exception {
        Properties app = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(app.getProperty("app.notifications.email.provider")).isEqualTo("${NOTIFICATIONS_EMAIL_PROVIDER:}");
        assertThat(app.getProperty("app.notifications.email.from")).isEqualTo("${NOTIFICATIONS_EMAIL_FROM:}");
        assertThat(app.getProperty("app.notifications.email.region")).isEqualTo("${AWS_REGION:}");
        assertThat(app.stringPropertyNames()).noneMatch(name -> name.contains("sms"));
        // AWS credentials are left to the AWS SDK's credential chain, never configured by the application.
        assertThat(app.values()).noneMatch(value -> value.toString().contains("AWS_ACCESS_KEY_ID")
                || value.toString().contains("AWS_SECRET_ACCESS_KEY"));
    }
}
