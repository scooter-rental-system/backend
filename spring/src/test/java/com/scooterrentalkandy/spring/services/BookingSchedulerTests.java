package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** The expiry job's cutoff and schedule, without Spring, a database or waiting. */
class BookingSchedulerTests {

    @Test
    void expiresBookingsCreatedMoreThanThirtyMinutesAgo() {
        BookingService bookingService = mock(BookingService.class);
        // 06:00 UTC is 11:30 in Colombo, the clock's zone.
        Clock clock = Clock.fixed(Instant.parse("2026-10-24T06:00:00Z"), ZoneId.of("Asia/Colombo"));

        new BookingScheduler(bookingService, clock).expireUnpaidBookings();

        verify(bookingService).expireUnpaid(LocalDateTime.of(2026, 10, 24, 11, 0));
    }

    @Test
    void runsEveryMinuteAfterAThirtySecondStartDelay() throws Exception {
        Scheduled schedule = BookingScheduler.class.getMethod("expireUnpaidBookings").getAnnotation(Scheduled.class);

        assertThat(schedule.fixedDelay()).isEqualTo(60_000);
        assertThat(schedule.initialDelay()).isEqualTo(30_000);
        assertThat(schedule.cron()).isEmpty();
    }

    @Test
    void expiryIsTheOnlyJobInBookingScheduler() {
        assertThat(Arrays.stream(BookingScheduler.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(Scheduled.class))
                .map(Method::getName))
                .containsExactly("expireUnpaidBookings");
    }
}
