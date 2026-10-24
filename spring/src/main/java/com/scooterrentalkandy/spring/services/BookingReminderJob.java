package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Customer reminders. Scheduled only when {@code app.scheduling.reminders.enabled=true} (off by default). Each
 * reminder is an in-app notification, plus email when an email provider is configured.
 *
 * <p>Each booking is claimed in its own transaction by setting {@code reminder_sent_at} only while it is still
 * empty, together with the in-app notification; so a re-run, or several application instances running the job at
 * once, send each booking at most one reminder, and a failure leaves the booking unclaimed for the next run.
 */
@Component
@ConditionalOnProperty(name = "app.scheduling.reminders.enabled", havingValue = "true")
public class BookingReminderJob {

    private static final Logger log = LoggerFactory.getLogger(BookingReminderJob.class);

    static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public BookingReminderJob(BookingRepository bookings, NotificationService notifications, Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.bookings = bookings;
        this.notifications = notifications;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * 08:00 Colombo time: reminders for paid rentals that start tomorrow (Colombo date). Rentals are open-ended,
     * so there is no due date. Returns how many reminders this run sent.
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "Asia/Colombo")
    public int dailyReminders() {
        LocalDate tomorrow = LocalDate.now(clock.withZone(COLOMBO)).plusDays(1);
        int sent = 0;
        for (UUID id : bookings.findReminderCandidates(BookingStatus.ACTIVE, tomorrow.atStartOfDay(),
                tomorrow.plusDays(1).atStartOfDay())) {
            try {
                if (Boolean.TRUE.equals(transaction.execute(status -> remind(id)))) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                // Rolled back with its claim, so the next run tries this booking again.
                log.error("Reminder for booking {} failed", id, ex);
            }
        }
        return sent;
    }

    private boolean remind(UUID id) {
        if (bookings.claimReminder(id, BookingStatus.ACTIVE, LocalDateTime.now(clock)) != 1) {
            return false;
        }
        Booking b = bookings.findById(id).orElseThrow();
        notifications.notify(b.getUser(), "Rental starts tomorrow: " + b.getReference(),
                "Your " + b.getScooter().getModel() + " is ready from " + b.getStartTime().format(TIME)
                        + " tomorrow" + (b.getPickupLocation() == null ? "." : " at " + b.getPickupLocation() + ".")
                        + " Bring your driving licence and ID.");
        return true;
    }
}
