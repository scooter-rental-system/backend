package com.scooterrentalkandy.spring.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Rental time is billed by the hour, so the clock is injectable (tests move it forward). */
@Configuration
public class TimeConfig {

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }
}
