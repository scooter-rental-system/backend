package com.scooterrentalkandy.spring.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Clock;
import java.util.List;
import java.util.Properties;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.services.BookingReminderJob;
import com.scooterrentalkandy.spring.services.BookingScheduler;
import com.scooterrentalkandy.spring.services.BookingService;
import com.scooterrentalkandy.spring.services.NotificationService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.scheduling.config.ScheduledTaskHolder;

/**
 * Which scheduled jobs exist under which properties. Enabled scheduling is checked in a small context with mocked
 * collaborators that is closed straight away, long before the 30-second initial delay, so no job ever runs and
 * nothing is sent. The test-profile check uses the normal application context.
 */
class SchedulingConfigTests extends PostgresIntegrationTest {

    @Autowired
    private ApplicationContext testContext;

    private final NotificationService notifications = mock(NotificationService.class);
    private final BookingService bookingService = mock(BookingService.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class, BookingScheduler.class, BookingReminderJob.class)
            .withBean(BookingService.class, () -> bookingService)
            .withBean(BookingRepository.class, () -> mock(BookingRepository.class))
            .withBean(NotificationService.class, () -> notifications)
            .withBean(Clock.class, Clock::systemDefaultZone);

    private static List<String> scheduledTasks(ApplicationContext context) {
        return context.getBeansOfType(ScheduledTaskHolder.class).values().stream()
                .flatMap(holder -> holder.getScheduledTasks().stream())
                .map(task -> task.toString())
                .toList();
    }

    @Test
    void applicationConfigurationEnablesSchedulingButNotReminders() throws Exception {
        Properties app = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(app.getProperty("app.scheduling.enabled")).isEqualTo("true");
        assertThat(app.getProperty("app.scheduling.reminders.enabled")).isEqualTo("false");
    }

    @Test
    void enabledSchedulingRegistersOnlyTheExpiryJob() {
        runner.withPropertyValues("app.scheduling.enabled=true").run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(BookingScheduler.class);
            assertThat(context).doesNotHaveBean(BookingReminderJob.class);
            assertThat(scheduledTasks(context)).singleElement().asString().contains("expireUnpaidBookings");
        });
        verifyNoInteractions(bookingService, notifications);
    }

    @Test
    void remindersStayOffWhenExplicitlyDisabled() {
        runner.withPropertyValues("app.scheduling.enabled=true", "app.scheduling.reminders.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(BookingReminderJob.class);
                    assertThat(scheduledTasks(context)).noneMatch(task -> task.contains("dailyReminders"));
                });
    }

    @Test
    void remindersAreRegisteredOnlyWhenExplicitlyEnabled() {
        runner.withPropertyValues("app.scheduling.enabled=true", "app.scheduling.reminders.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(BookingReminderJob.class);
                    assertThat(scheduledTasks(context)).hasSize(2)
                            .anyMatch(task -> task.contains("dailyReminders"))
                            .anyMatch(task -> task.contains("expireUnpaidBookings"));
                });
        // Registered, never run: nothing was sent.
        verifyNoInteractions(notifications, bookingService);
    }

    @Test
    void schedulingIsOffWithoutTheProperty() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(scheduledTasks(context)).isEmpty();
        });
        runner.withPropertyValues("app.scheduling.enabled=false").run(context -> {
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(scheduledTasks(context)).isEmpty();
        });
    }

    @Test
    void theTestProfileRunsNoScheduledJobs() {
        assertThat(testContext.getBeanNamesForType(SchedulingConfig.class)).isEmpty();
        assertThat(testContext.getBeanNamesForType(BookingReminderJob.class)).isEmpty();
        assertThat(scheduledTasks(testContext)).isEmpty();
        // The expiry bean still exists for direct calls; it is just never scheduled.
        assertThat(testContext.getBeanNamesForType(BookingScheduler.class)).hasSize(1);
    }
}
