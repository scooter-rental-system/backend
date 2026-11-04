package com.scooterrentalkandy.spring.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Runs {@code @Scheduled} jobs only when {@code app.scheduling.enabled=true}: set in the application's
 * configuration, off in the test profile so no job runs in the background of automated tests.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true")
public class SchedulingConfig {
}
