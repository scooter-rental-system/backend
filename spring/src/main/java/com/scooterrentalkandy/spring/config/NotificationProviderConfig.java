package com.scooterrentalkandy.spring.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

/**
 * Selects the email provider, failing closed. {@code app.notifications.email.provider}
 * (NOTIFICATIONS_EMAIL_PROVIDER):
 * <ul>
 *   <li>empty: no EmailSender bean; email is not sent and no email delivery is recorded. In-app notifications
 *       work as normal.</li>
 *   <li>anything else (including "mock"): startup fails. There is no stand-in sender.</li>
 * </ul>
 * There is no SMS provider: no SMS is sent or recorded.
 */
@Configuration
public class NotificationProviderConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationProviderConfig.class);

    public NotificationProviderConfig(AppProperties props) {
        AppProperties.Notifications.Email email = props.notifications() == null ? null : props.notifications().email();
        String provider = email == null || email.provider() == null ? "" : email.provider();
        if (provider.isBlank()) {
            log.info("No email provider configured (NOTIFICATIONS_EMAIL_PROVIDER): notifications are in-app only");
        } else {
            throw new IllegalStateException("Unsupported email provider '" + provider
                    + "' (NOTIFICATIONS_EMAIL_PROVIDER). Leave it empty to run with in-app notifications only.");
        }
    }
}
