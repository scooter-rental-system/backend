package com.scooterrentalkandy.spring.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import com.scooterrentalkandy.spring.services.SesEmailSender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the email provider, failing closed. {@code app.notifications.email.provider}
 * (NOTIFICATIONS_EMAIL_PROVIDER):
 * <ul>
 *   <li>empty: no EmailSender bean; email is not sent and no email delivery is recorded. In-app notifications
 *       work as normal.</li>
 *   <li>{@code ses}: Amazon SES. Startup fails unless the sender address (NOTIFICATIONS_EMAIL_FROM) and region
 *       (AWS_REGION) are set. AWS credentials come from the AWS SDK's default credential chain and are not
 *       checked or read here.</li>
 *   <li>anything else (including "mock"): startup fails. There is no stand-in sender.</li>
 * </ul>
 * There is no SMS provider: no SMS is sent or recorded.
 */
@Configuration
public class NotificationProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderConfig.class);

    public static final String SES = "ses";

    /** Enough to catch a missing or mistyped setting; SES itself verifies the sender identity. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern REGION = Pattern.compile("^[a-z]{2}(-[a-z]+)+-\\d+$");

    private final AppProperties.Notifications.Email email;

    public NotificationProviderConfig(AppProperties props) {
        this.email = props.notifications() == null ? null : props.notifications().email();
        String provider = email == null || email.provider() == null ? "" : email.provider();
        if (provider.isBlank()) {
            log.info("No email provider configured (NOTIFICATIONS_EMAIL_PROVIDER): notifications are in-app only");
        } else if (!SES.equalsIgnoreCase(provider)) {
            throw new IllegalStateException("Unsupported email provider '" + provider
                    + "' (NOTIFICATIONS_EMAIL_PROVIDER). The only supported provider is 'ses'; leave it empty to run "
                    + "with in-app notifications only.");
        } else {
            requireSesSettings(email);
        }
    }

    @Bean
    @ConditionalOnProperty(name = "app.notifications.email.provider", havingValue = SES)
    SesEmailSender sesEmailSender() {
        log.info("Email notifications are sent through Amazon SES in {}", email.region().trim());
        return new SesEmailSender(SesEmailSender.client(email.region().trim()), email.from().trim());
    }

    private static void requireSesSettings(AppProperties.Notifications.Email email) {
        List<String> problems = new ArrayList<>();
        if (isBlank(email.from())) {
            problems.add("NOTIFICATIONS_EMAIL_FROM is not set");
        } else if (!EMAIL.matcher(email.from().trim()).matches()) {
            problems.add("NOTIFICATIONS_EMAIL_FROM is not an email address");
        }
        if (isBlank(email.region())) {
            problems.add("AWS_REGION is not set");
        } else if (!REGION.matcher(email.region().trim()).matches()) {
            problems.add("AWS_REGION is not an AWS region name");
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("NOTIFICATIONS_EMAIL_PROVIDER is 'ses' but " + String.join(" and ", problems)
                    + ". Configure them in the environment.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
