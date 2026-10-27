package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CreateBookingRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CompleteMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.CreateMaintenanceRequest;
import com.scooterrentalkandy.spring.dto.MaintenanceDtos.MaintenanceResponse;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Booking creation and maintenance changes on the same scooter, each in its own transaction and connection
 * against the embedded PostgreSQL. Both lock the scooter row, so they run one after the other and each sees the
 * other's committed result. As in GearConcurrencyTests, the waiting is proven with PostgreSQL's own lock state
 * (test-only) under a deadline; no fixed sleeps. Fixtures are committed and deleted after each test.
 */
class MaintenanceConcurrencyTests extends PostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private MaintenanceRepository records;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private UserRepository users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private ExecutorService executor;
    private TransactionTemplate transaction;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();

    @BeforeEach
    void start() {
        executor = Executors.newFixedThreadPool(2);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void stopAndDeleteFixtures() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (UUID scooter : createdScooters) {
            String bookingsOf = "SELECT booking_id FROM bookings WHERE scooter_id = ?";
            jdbc.update("DELETE FROM contracts WHERE booking_id IN (" + bookingsOf + ")", scooter);
            jdbc.update("DELETE FROM booking_gear WHERE booking_id IN (" + bookingsOf + ")", scooter);
            jdbc.update("DELETE FROM bookings WHERE scooter_id = ?", scooter);
            jdbc.update("DELETE FROM maintenance_records WHERE scooter_id = ?", scooter);
            jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", scooter);
        }
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    // ── Fixtures (committed) ──────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveCustomer() {
        String id = unique();
        User u = new User();
        u.setEmail("mrace." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Maintenance Race " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("Q" + id);
        u.setRole(Role.USER);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("MR-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("MRP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Scooter saved = scooters.saveAndFlush(s);
        createdScooters.add(saved.getScooterId());
        return saved;
    }

    private UUID scheduleWork(Scooter scooter, boolean startNow) {
        return maintenanceService.create(new CreateMaintenanceRequest(scooter.getScooterId(),
                MaintenanceRecord.Type.REPAIR, "Brake pads", null, startNow)).id();
    }

    private Callable<BookingResponse> booking(Scooter scooter) {
        User customer = saveCustomer();
        CreateBookingRequest request = new CreateBookingRequest(scooter.getScooterId(),
                LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES), List.of(), "Kandy Lake Round", null);
        return () -> bookingService.create(customer.getUserId(), request);
    }

    private ScooterStatus statusOf(Scooter scooter) {
        return scooters.findById(scooter.getScooterId()).orElseThrow().getStatus();
    }

    private MaintenanceRecord.Status statusOf(UUID record) {
        return records.findById(record).orElseThrow().getStatus();
    }

    private long bookingsOn(Scooter scooter) {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE scooter_id = ? AND status IN "
                + "('PENDING', 'ACTIVE')", Long.class, scooter.getScooterId());
    }

    private static <T> T result(Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    /** Waits, with a deadline, until another session is blocked on a row lock while touching scooters. */
    private void awaitSessionWaitingOnScooterLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock' AND query ILIKE '%scooters%'", Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                fail("No session was blocked on a scooter row lock within " + TIMEOUT_SECONDS + " s");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    /** Runs {@code work} in a transaction that stays open (holding its locks) until {@code release} opens. */
    private <T> Future<T> heldOpen(Callable<T> work, CountDownLatch done, CountDownLatch release) {
        return executor.submit(() -> transaction.execute(status -> {
            try {
                T result = work.call();
                done.countDown();
                if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
                return result;
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
    }

    // ── Booking vs. starting maintenance ──────────────────────────────────────

    @Test
    void maintenanceWaitsForABookingAndThenRefusesTheBookedScooter() throws Exception {
        Scooter scooter = saveScooter();
        UUID work = scheduleWork(scooter, false);
        CountDownLatch booked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<BookingResponse> booking = heldOpen(booking(scooter), booked, release);
        Future<MaintenanceResponse> start;
        try {
            assertThat(booked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            start = executor.submit(() -> maintenanceService.start(work));

            awaitSessionWaitingOnScooterLock();
            assertThat(start.isDone()).as("starting work must wait for the booking's scooter lock").isFalse();
        } finally {
            release.countDown();
        }

        assertThat(result(booking).status()).isEqualTo(BookingStatus.PENDING);
        assertThatThrownBy(() -> result(start))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo(MaintenanceService.BOOKED);
                });
        assertThat(statusOf(work)).isEqualTo(MaintenanceRecord.Status.SCHEDULED);
        assertThat(statusOf(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void aBookingWaitsForMaintenanceAndThenSeesTheScooterInTheWorkshop() throws Exception {
        Scooter scooter = saveScooter();
        UUID work = scheduleWork(scooter, false);
        Callable<BookingResponse> book = booking(scooter);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<MaintenanceResponse> start = heldOpen(() -> maintenanceService.start(work), started, release);
        Future<BookingResponse> booking;
        try {
            assertThat(started.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            booking = executor.submit(book);

            awaitSessionWaitingOnScooterLock();
            assertThat(booking.isDone()).as("the booking must wait for the maintenance scooter lock").isFalse();
        } finally {
            release.countDown();
        }

        assertThat(result(start).status()).isEqualTo(MaintenanceRecord.Status.IN_PROGRESS);
        assertThatThrownBy(() -> result(booking))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(bookingsOn(scooter)).isZero();
        assertThat(statusOf(scooter)).isEqualTo(ScooterStatus.MAINTENANCE);
    }

    /** Started together, whatever the timing: never a held booking and work in progress on one scooter. */
    @Test
    void aBookingAndMaintenanceStartedTogetherNeverBothWin() throws Exception {
        Scooter scooter = saveScooter();
        UUID work = scheduleWork(scooter, false);
        Callable<BookingResponse> book = booking(scooter);
        CyclicBarrier together = new CyclicBarrier(2);

        Future<BookingResponse> booking = executor.submit(() -> {
            together.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return book.call();
        });
        Future<MaintenanceResponse> start = executor.submit(() -> {
            together.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return maintenanceService.start(work);
        });

        boolean bookingWon = succeeded(booking);
        boolean maintenanceWon = succeeded(start);
        assertThat(bookingWon ^ maintenanceWon).as("exactly one must win").isTrue();
        assertThat(bookingsOn(scooter)).isEqualTo(bookingWon ? 1 : 0);
        assertThat(statusOf(work))
                .isEqualTo(maintenanceWon ? MaintenanceRecord.Status.IN_PROGRESS : MaintenanceRecord.Status.SCHEDULED);
        assertThat(statusOf(scooter)).isEqualTo(maintenanceWon ? ScooterStatus.MAINTENANCE : ScooterStatus.AVAILABLE);
    }

    private static boolean succeeded(Future<?> attempt) throws Exception {
        try {
            result(attempt);
            return true;
        } catch (ApiException e) {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            return false;
        }
    }

    @Test
    void startingWorkOnABookedScooterLeavesNoRecordBehind() throws Exception {
        Scooter scooter = saveScooter();
        result(executor.submit(booking(scooter)));

        ApiException ex = catchThrowableOfType(ApiException.class, () -> scheduleWork(scooter, true));

        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(records.findByScooter_ScooterIdOrderByCreatedAtDesc(scooter.getScooterId())).isEmpty();
        assertThat(statusOf(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    // ── Completing the last open work ─────────────────────────────────────────

    /**
     * Two in-progress jobs completed at once: the second waits for the first's scooter lock, then sees it DONE
     * and returns the scooter to service. Without the lock each saw the other still in progress and neither did.
     */
    @Test
    void completingTheLastTwoJobsConcurrentlyReturnsTheScooter() throws Exception {
        Scooter scooter = saveScooter();
        UUID first = scheduleWork(scooter, true);
        UUID second = scheduleWork(scooter, true);
        CompleteMaintenanceRequest done = new CompleteMaintenanceRequest(null, null, null);
        CountDownLatch firstCompleted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<MaintenanceResponse> a = heldOpen(() -> maintenanceService.complete(first, done), firstCompleted,
                release);
        Future<MaintenanceResponse> b;
        try {
            assertThat(firstCompleted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            b = executor.submit(() -> maintenanceService.complete(second, done));

            awaitSessionWaitingOnScooterLock();
            assertThat(b.isDone()).as("the second completion must wait for the first's scooter lock").isFalse();
        } finally {
            release.countDown();
        }

        assertThat(result(a).status()).isEqualTo(MaintenanceRecord.Status.DONE);
        assertThat(result(b).status()).isEqualTo(MaintenanceRecord.Status.DONE);
        assertThat(statusOf(first)).isEqualTo(MaintenanceRecord.Status.DONE);
        assertThat(statusOf(second)).isEqualTo(MaintenanceRecord.Status.DONE);
        assertThat(statusOf(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }
}
