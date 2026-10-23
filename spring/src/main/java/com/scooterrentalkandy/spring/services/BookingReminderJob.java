package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer reminders. Exists only when {@code app.scheduling.reminders.enabled=true}: the only message sender
 * today is the mock, so reminders stay off until a real notification provider is integrated.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.reminders.enabled", havingValue = "true")
public class BookingReminderJob {

    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final Clock clock;

    public BookingReminderJob(BookingRepository bookings, NotificationService notifications, Clock clock) {
        this.bookings = bookings;
        this.notifications = notifications;
        this.clock = clock;
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
