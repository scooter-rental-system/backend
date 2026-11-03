package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Confirmations arriving at the same moment, against committed transactions on the embedded PostgreSQL: every
 * confirmation locks the booking row first, so concurrent or repeated customer confirmations start the rental exactly
 * once (one actual start, one ACTIVE transition, one notification), and a repeated handover racing the start never
 * rewrites the reading. Fixtures are committed and deleted after each test.
 */
class RentalStartConcurrencyTests extends PostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;
    private static final int THREADS = 6;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService executor;
    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();

    @BeforeEach
    void start() {
        executor = Executors.newFixedThreadPool(THREADS);
    }

    @AfterEach
    void stopAndDeleteFixtures() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (UUID user : createdUsers) {
            jdbc.update("DELETE FROM contracts WHERE booking_id IN (SELECT booking_id FROM bookings WHERE user_id = ?)",
                    user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
        }
        createdUsers.forEach(id -> jdbc.update("DELETE FROM notifications WHERE user_id = ?", id));
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("race." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Race " + role + " " + id);
        u.setNicNumber("R" + id);
        u.setRole(role);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    /** A committed READY_FOR_PICKUP booking for {@code customer}. */
    private Booking readyBooking(User customer) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RC-" + id);
        s.setModel("Race Scooter");
        s.setPlateNumber("RCP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        scooters.saveAndFlush(s);
        createdScooters.add(s.getScooterId());
        Booking b = new Booking();
        b.setReference("BK-RC" + id);
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(BookingStatus.READY_FOR_PICKUP);
        b.setStartTime(LocalDateTime.now().plusHours(1));
        b.setContractSigned(true);
        b.setPickupLocation("Test pickup");
        return bookings.saveAndFlush(b);
    }

    private static AuthUser auth(User u) {
        return new AuthUser(u.getUserId(), u.getEmail(), u.getRole());
    }

    private long notifications(User user, String subjectPrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND subject LIKE ?",
                Long.class, user.getUserId(), subjectPrefix + "%");
    }

    @Test
    void concurrentStartConfirmationsActivateTheRentalExactlyOnce() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        Booking b = readyBooking(customer);
        bookingService.confirmHandover(b.getBookingId(), auth(admin), new BigDecimal("2500.0"));

        CountDownLatch go = new CountDownLatch(1);
        List<Future<BookingResponse>> confirmations = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            confirmations.add(executor.submit(() -> {
                assertThat(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return bookingService.confirmStart(b.getBookingId(), auth(customer));
            }));
        }
        go.countDown();
        List<LocalDateTime> startTimes = new ArrayList<>();
        for (Future<BookingResponse> f : confirmations) {
            BookingResponse r = f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(r.status()).isEqualTo(BookingStatus.ACTIVE);
            startTimes.add(r.actualStartTime());
        }

        Booking started = bookings.findById(b.getBookingId()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(startTimes).as("every caller sees the one recorded start").containsOnly(started.getActualStartTime());
        assertThat(started.getCustomerStartConfirmedAt()).isEqualTo(started.getActualStartTime());
        assertThat(scooters.findById(b.getScooter().getScooterId()).orElseThrow().getStatus())
                .isEqualTo(ScooterStatus.RENTED);
        assertThat(notifications(customer, "Rental started")).isEqualTo(1);
        assertThat(notifications(admin, "Rental started")).isEqualTo(1);
    }

    @Test
    void aRepeatedHandoverRacingTheStartNeitherBlocksNorRewritesIt() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        Booking b = readyBooking(customer);
        bookingService.confirmHandover(b.getBookingId(), auth(admin), new BigDecimal("2500.0"));

        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> calls = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            boolean customerCall = i % 2 == 0;
            calls.add(executor.submit(() -> {
                assertThat(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                return customerCall ? bookingService.confirmStart(b.getBookingId(), auth(customer))
                        : bookingService.confirmHandover(b.getBookingId(), auth(admin), new BigDecimal("2500.0"));
            }));
        }
        go.countDown();
        for (Future<?> f : calls) {
            f.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        Booking started = bookings.findById(b.getBookingId()).orElseThrow();
        assertThat(started.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(started.getStartOdometerKm()).isEqualByComparingTo("2500.0");
        assertThat(started.getActualStartTime()).isNotNull();
        assertThat(notifications(customer, "Rental started")).isEqualTo(1);
        assertThat(notifications(customer, "Scooter handed over")).isEqualTo(1);
    }
}
