package com.scooterrentalkandy.spring.services;


import java.time.Clock;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Unpaid-booking expiry. Runs only when scheduling is enabled (see SchedulingConfig); reminders are separate. */
@Component
public class BookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingScheduler.class);

    /** How long an unpaid booking holds the scooter. */
    static final int PAYMENT_HOLD_MINUTES = 30;

    private final BookingService bookingService;
    private final Clock clock;

    public BookingScheduler(BookingService bookingService, Clock clock) {
        this.bookingService = bookingService;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expireUnpaidBookings() {
        int n = bookingService.expireUnpaid(LocalDateTime.now(clock).minusMinutes(PAYMENT_HOLD_MINUTES));
        if (n > 0) {
            log.info("Expired {} unpaid booking(s)", n);
        }
    }
}
