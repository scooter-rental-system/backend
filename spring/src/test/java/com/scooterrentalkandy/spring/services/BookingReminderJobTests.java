package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Notification.Channel;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * BookingReminderJob against the embedded database, called directly (it is never scheduled in tests and stays off
 * by default). The job's claims really commit, so each test uses its own far-future dates: no other test's bookings
 * start then. No email provider is configured, so reminders are in-app only.
 */
class BookingReminderJobTests extends PostgresIntegrationTest {

    private static final ZoneId COLOMBO = ZoneId.of("Asia/Colombo");

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private NotificationRepository rows;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    /** Committed fixtures, deleted after each test so the shared test database is left as it was. */
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();

    @AfterEach
    void deleteFixtures() {
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
        }
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
        createdUsers.clear();
        createdScooters.clear();
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    /** The job at a fixed instant; the clock's zone is UTC so the Colombo date has to be worked out by the job. */
    private BookingReminderJob jobAt(String instant) {
        return new BookingReminderJob(bookings, notifications, Clock.fixed(Instant.parse(instant), ZoneId.of("UTC")),
                transactionManager);
    }

    private User customer(boolean active) {
        String id = unique();
        User u = new User();
        u.setEmail("remind." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Remind " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("R" + id);
        u.setRole(Role.USER);
        u.setIsActive(active);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Booking booking(BookingStatus status, LocalDateTime start) {
        return booking(customer(true), status, start);
    }

    private Booking booking(User user, BookingStatus status, LocalDateTime start) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RM-" + id);
        s.setModel("Reminder Scooter");
        s.setPlateNumber("RMP-" + id);
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Booking b = new Booking();
        b.setReference("BK-RM" + id);
        b.setUser(user);
        Scooter scooter = scooters.saveAndFlush(s);
        createdScooters.add(scooter.getScooterId());
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(start);
        b.setPickupLocation("Kandy Lake");
        if (status == BookingStatus.CANCELLED) {
            b.setCancelledAt(start.minusDays(2));
        }
        return bookings.saveAndFlush(b);
    }

    private List<Notification> inApp(Booking b) {
        return rows.findByUser_UserIdAndChannelOrderByCreatedAtDesc(b.getUser().getUserId(), Channel.IN_APP);
    }

    private LocalDateTime reminderSentAt(Booking b) {
        return bookings.findById(b.getBookingId()).orElseThrow().getReminderSentAt();
    }

    @Test
    void remindsPaidRentalsStartingTomorrowExactlyOnce() {
        // 08:00 Colombo on 1 June 2099 is 02:30 UTC.
        BookingReminderJob job = jobAt("2099-06-01T02:30:00Z");
        Booking tomorrow = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 6, 2, 9, 30));
        Booking lastMinuteOfTomorrow = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 6, 2, 23, 59));
        Booking today = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 6, 1, 18, 0));
        Booking dayAfter = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 6, 3, 0, 0));
        Booking unpaid = booking(BookingStatus.PENDING, LocalDateTime.of(2099, 6, 2, 10, 0));
        Booking cancelled = booking(BookingStatus.CANCELLED, LocalDateTime.of(2099, 6, 2, 11, 0));

        assertThat(job.dailyReminders()).isEqualTo(2);

        assertThat(inApp(tomorrow)).singleElement().satisfies(n -> {
            assertThat(n.getSubject()).isEqualTo("Rental starts tomorrow: " + tomorrow.getReference());
            assertThat(n.getMessage()).isEqualTo("Your Reminder Scooter is ready from 09:30 tomorrow at Kandy Lake."
                    + " Bring your driving licence and ID.");
        });
        assertThat(reminderSentAt(tomorrow)).isEqualTo(LocalDateTime.of(2099, 6, 1, 2, 30));
        assertThat(inApp(lastMinuteOfTomorrow)).hasSize(1);
        for (Booking none : List.of(today, dayAfter, unpaid, cancelled)) {
            assertThat(inApp(none)).isEmpty();
            assertThat(reminderSentAt(none)).isNull();
        }
        // No email provider: in-app only, nothing recorded as emailed.
        assertThat(rows.findByUser_UserIdAndChannelOrderByCreatedAtDesc(tomorrow.getUser().getUserId(),
                Channel.EMAIL)).isEmpty();

        // Running again (or the next minute, or after a restart) sends nothing more.
        assertThat(job.dailyReminders()).isZero();
        assertThat(jobAt("2099-06-01T05:00:00Z").dailyReminders()).isZero();
        assertThat(inApp(tomorrow)).hasSize(1);
        assertThat(reminderSentAt(tomorrow)).isEqualTo(LocalDateTime.of(2099, 6, 1, 2, 30));
    }

    @Test
    void instancesRunningAtTheSameTimeSendOneReminderPerBooking() throws Exception {
        List<Booking> due = List.of(
                booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 7, 2, 9, 0)),
                booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 7, 2, 12, 0)),
                booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 7, 2, 15, 0)));
        int instances = 4;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Integer>> runs = new ArrayList<>();
        for (int i = 0; i < instances; i++) {
            BookingReminderJob instance = jobAt("2099-07-01T02:30:00Z");
            runs.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return instance.dailyReminders();
            }));
        }
        start.countDown();

        int total = 0;
        for (CompletableFuture<Integer> run : runs) {
            total += run.get(30, TimeUnit.SECONDS);
        }

        assertThat(total).isEqualTo(due.size());
        for (Booking b : due) {
            assertThat(inApp(b)).hasSize(1);
            assertThat(reminderSentAt(b)).isNotNull();
        }
    }

    @Test
    void tomorrowIsTheColomboDate() {
        // 19:00 UTC on 1 Aug is already 00:30 on 2 Aug in Colombo, so "tomorrow" is 3 Aug.
        BookingReminderJob job = jobAt("2099-08-01T19:00:00Z");
        Booking utcTomorrow = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 8, 2, 10, 0));
        Booking colomboTomorrow = booking(BookingStatus.ACTIVE, LocalDateTime.of(2099, 8, 3, 10, 0));

        job.dailyReminders();

        assertThat(inApp(colomboTomorrow)).hasSize(1);
        assertThat(inApp(utcTomorrow)).isEmpty();
        assertThat(LocalDateTime.ofInstant(Instant.parse("2099-08-01T19:00:00Z"), COLOMBO).toLocalDate())
                .hasToString("2099-08-02");
    }

    @Test
    void anInactiveCustomerIsClaimedButNotNotified() {
        Booking b = booking(customer(false), BookingStatus.ACTIVE, LocalDateTime.of(2099, 9, 2, 10, 0));

        jobAt("2099-09-01T02:30:00Z").dailyReminders();

        assertThat(inApp(b)).isEmpty();
        assertThat(reminderSentAt(b)).isNotNull();
    }
}
