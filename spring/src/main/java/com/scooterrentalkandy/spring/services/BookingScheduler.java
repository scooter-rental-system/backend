package com.scooterrentalkandy.spring.services;


import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BookingScheduler {

    private static final Logger log = LoggerFactory.getLogger(BookingScheduler.class);

    /** How long an unpaid booking holds the scooter. */
    static final int PAYMENT_HOLD_MINUTES = 30;

    private final BookingService bookingService;
    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final Clock clock;

    public BookingScheduler(BookingService bookingService, BookingRepository bookings,
                            NotificationService notifications, Clock clock) {
        this.bookingService = bookingService;
        this.bookings = bookings;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 30_000)
    public void expireUnpaidBookings() {
        int n = bookingService.expireUnpaid(LocalDateTime.now(clock).minusMinutes(PAYMENT_HOLD_MINUTES));
        if (n > 0) {
            log.info("Expired {} unpaid booking(s)", n);
        }
    }

    /** 08:00 Colombo time: reminders for paid rentals that start tomorrow. Rentals are open-ended, so no due date. */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Colombo")
    @Transactional
    public void dailyReminders() {
        LocalDate tomorrow = LocalDate.now(clock).plusDays(1);
        DateTimeFormatter time = DateTimeFormatter.ofPattern("HH:mm");
        bookings.findByStatusAndStartTimeBetween(BookingStatus.ACTIVE, tomorrow.atStartOfDay(),
                tomorrow.plusDays(1).atStartOfDay()).forEach(b ->
                notifications.notify(b.getUser(), "Rental starts tomorrow: " + b.getReference(),
                        "Your " + b.getScooter().getModel() + " is ready from " + b.getStartTime().format(time)
                                + " tomorrow" + (b.getPickupLocation() == null ? "." : " at " + b.getPickupLocation()
                                + ".") + " Bring your driving licence and ID."));
    }
}
