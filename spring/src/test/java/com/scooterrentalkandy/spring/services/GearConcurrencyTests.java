package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.scooterrentalkandy.spring.dto.BookingDtos.GearSelectionRequest;
import com.scooterrentalkandy.spring.dto.GearDtos.GearRequest;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingGearRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Concurrent bookings that reserve the same gear, against the embedded PostgreSQL, each in its own transaction
 * and connection. Fixtures are committed and deleted after each test; every wait is bounded.
 *
 * <p>To prove that the gear row lock, not scheduling luck, protects the stock, two tests read PostgreSQL's own
 * lock state (test-only): they wait, with a deadline, until {@code pg_stat_activity} shows the second booking
 * waiting on a lock, and probe which gear rows are locked with {@code FOR UPDATE NOWAIT}. No fixed sleeps.
 */
class GearConcurrencyTests extends PostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private GearService gearService;

    @Autowired
    private BookingGearRepository bookingGear;

    @Autowired
    private GearItemRepository gearItems;

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
    private final List<UUID> createdGear = new ArrayList<>();

    @BeforeEach
    void start() {
        executor = Executors.newFixedThreadPool(2);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void stopAndDeleteFixtures() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        for (UUID user : createdUsers) {
            String bookingsOf = "SELECT booking_id FROM bookings WHERE user_id = ?";
            jdbc.update("DELETE FROM contracts WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM booking_gear WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
        }
        createdGear.forEach(id -> jdbc.update("DELETE FROM gear_items WHERE gear_item_id = ?", id));
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    // ── Fixtures (committed) ──────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser() {
        String id = unique();
        User u = new User();
        u.setEmail("race." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Race Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("R" + id);
        u.setRole(Role.USER);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("R-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("RP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Scooter saved = scooters.saveAndFlush(s);
        createdScooters.add(saved.getScooterId());
        return saved;
    }

    private GearItem saveGear(int stock) {
        GearItem g = new GearItem();
        g.setName("Race Gear " + unique());
        g.setCategory(Category.CAMPING);
        g.setDailyRate(new BigDecimal("500.00"));
        g.setTotalQuantity(stock);
        GearItem saved = gearItems.saveAndFlush(g);
        createdGear.add(saved.getGearItemId());
        return saved;
    }

    /** A booking by a new customer on a new scooter (so only the gear can make them compete). */
    private Callable<BookingResponse> booking(GearItem... gear) {
        User customer = saveUser();
        Scooter scooter = saveScooter();
        List<GearSelectionRequest> lines = new ArrayList<>();
        for (GearItem g : gear) {
            lines.add(new GearSelectionRequest(g.getGearItemId(), 1));
        }
        CreateBookingRequest request = new CreateBookingRequest(scooter.getScooterId(),
                LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES), lines, "Kandy Lake Round", null);
        return () -> bookingService.create(customer.getUserId(), request);
    }

    private long reserved(GearItem gear) {
        return bookingGear.sumReservedQuantity(gear.getGearItemId(), BookingStatus.BLOCKING);
    }

    private long bookingsHolding(GearItem gear) {
        return jdbc.queryForObject("SELECT count(DISTINCT booking_id) FROM booking_gear WHERE gear_item_id = ?",
                Long.class, gear.getGearItemId());
    }

    private static <T> T result(Future<T> future) throws Exception {
        try {
            return future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            throw (Exception) e.getCause();
        }
    }

    /** Waits, with a deadline, until another session is blocked on a row lock while touching gear_items. */
    private void awaitSessionWaitingOnGearLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock' AND query ILIKE '%gear_items%'", Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                fail("No session was blocked on a gear row lock within " + TIMEOUT_SECONDS + " s");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    /** Whether another transaction currently holds a lock on this gear row (probe without waiting). */
    private boolean isLockedByAnotherTransaction(UUID gearId) {
        try {
            transaction.executeWithoutResult(status -> jdbc.queryForList(
                    "SELECT gear_item_id FROM gear_items WHERE gear_item_id = ? FOR UPDATE NOWAIT", gearId));
            return false;
        } catch (DataAccessException e) {
            return true;
        }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    /**
     * Transaction A books the last unit and stays open. B, on another scooter, must wait for A's gear lock
     * instead of reading the stock as free; once A commits, B sees A's reservation and gets the normal 409.
     */
    @Test
    void aCompetingBookingWaitsForTheGearLockAndThenSeesTheReservation() throws Exception {
        GearItem tent = saveGear(1);
        Callable<BookingResponse> first = booking(tent);
        Callable<BookingResponse> second = booking(tent);
        CountDownLatch firstBooked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);

        Future<BookingResponse> a = executor.submit(() -> transaction.execute(status -> {
            try {
                BookingResponse booked = first.call();
                firstBooked.countDown();
                // Keep the transaction, and its gear lock, open until the test lets it commit.
                if (!releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
                return booked;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        Future<BookingResponse> b;
        try {
            assertThat(firstBooked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            b = executor.submit(second);

            awaitSessionWaitingOnGearLock();
            assertThat(b.isDone()).as("second booking must still be waiting while the first holds the lock").isFalse();
        } finally {
            releaseFirst.countDown();
        }

        assertThat(result(a).status()).isEqualTo(BookingStatus.PENDING);
        assertThatThrownBy(() -> result(b))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("Only 0 x " + tent.getName() + " available right now");
                });
        assertThat(reserved(tent)).isEqualTo(1);
        assertThat(bookingsHolding(tent)).isEqualTo(1);
    }

    /** Two bookings for the last unit started together: exactly one wins, whatever the timing. */
    @Test
    void simultaneousBookingsForTheLastUnitReserveItOnce() throws Exception {
        GearItem tent = saveGear(1);
        CyclicBarrier together = new CyclicBarrier(2);
        List<Future<BookingResponse>> attempts = new ArrayList<>();
        for (Callable<BookingResponse> attempt : List.of(booking(tent), booking(tent))) {
            attempts.add(executor.submit(() -> {
                together.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return attempt.call();
            }));
        }

        int succeeded = 0;
        int conflicts = 0;
        for (Future<BookingResponse> attempt : attempts) {
            try {
                assertThat(result(attempt).status()).isEqualTo(BookingStatus.PENDING);
                succeeded++;
            } catch (ApiException e) {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).isEqualTo("Only 0 x " + tent.getName() + " available right now");
                conflicts++;
            }
        }
        assertThat(succeeded).isEqualTo(1);
        assertThat(conflicts).isEqualTo(1);
        assertThat(reserved(tent)).isEqualTo(1);
        assertThat(bookingsHolding(tent)).isEqualTo(1);
    }

    /** [A, B] and [B, A] started together must both finish: gear is locked in one global order. */
    @Test
    void bookingsRequestingTheSameGearInOppositeOrderDoNotDeadlock() throws Exception {
        GearItem helmet = saveGear(2);
        GearItem tent = saveGear(2);
        CyclicBarrier together = new CyclicBarrier(2);
        List<Future<BookingResponse>> attempts = new ArrayList<>();
        for (Callable<BookingResponse> attempt : List.of(booking(helmet, tent), booking(tent, helmet))) {
            attempts.add(executor.submit(() -> {
                together.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
                return attempt.call();
            }));
        }

        for (Future<BookingResponse> attempt : attempts) {
            assertThat(result(attempt).gear()).hasSize(2);
        }
        assertThat(reserved(helmet)).isEqualTo(2);
        assertThat(reserved(tent)).isEqualTo(2);
    }

    private GearRequest stock(GearItem gear, int totalQuantity) {
        return new GearRequest(gear.getName(), gear.getCategory(), gear.getDescription(), gear.getDailyRate(),
                totalQuantity, true);
    }

    private int storedStock(GearItem gear) {
        return gearItems.findById(gear.getGearItemId()).orElseThrow().getTotalQuantity();
    }

    /**
     * BE-031: an open booking holds the last unit; an admin lowering stock to 0 must wait for its lock and, after
     * the booking commits, see the reservation and get 409.
     */
    @Test
    void anAdminStockReductionWaitsForABookingAndThenSeesItsReservation() throws Exception {
        GearItem tent = saveGear(1);
        Callable<BookingResponse> book = booking(tent);
        CountDownLatch booked = new CountDownLatch(1);
        CountDownLatch releaseBooking = new CountDownLatch(1);

        Future<BookingResponse> booking = executor.submit(() -> transaction.execute(status -> {
            try {
                BookingResponse result = book.call();
                booked.countDown();
                if (!releaseBooking.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("not released");
                }
                return result;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        Future<?> reduce;
        try {
            assertThat(booked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            reduce = executor.submit(() -> gearService.update(tent.getGearItemId(), stock(tent, 0)));

            awaitSessionWaitingOnGearLock();
            assertThat(reduce.isDone()).as("the stock update must wait for the booking's gear lock").isFalse();
        } finally {
            releaseBooking.countDown();
        }

        assertThat(result(booking).status()).isEqualTo(BookingStatus.PENDING);
        assertThatThrownBy(() -> result(reduce))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage())
                            .isEqualTo("Stock cannot be lower than the 1 unit(s) reserved by pending or active bookings");
                });
        assertThat(storedStock(tent)).isEqualTo(1);
        assertThat(reserved(tent)).isEqualTo(1);
    }

    /** BE-031, the other order: a committed stock reduction is seen by a booking that waited for the lock. */
    @Test
    void aBookingWaitsForAnAdminStockReductionAndThenSeesTheNewStock() throws Exception {
        GearItem tent = saveGear(1);
        Callable<BookingResponse> book = booking(tent);
        CountDownLatch reduced = new CountDownLatch(1);
        CountDownLatch releaseReduction = new CountDownLatch(1);

        Future<?> reduce = executor.submit(() -> transaction.executeWithoutResult(status -> {
            gearService.update(tent.getGearItemId(), stock(tent, 0));
            reduced.countDown();
            try {
                releaseReduction.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Future<BookingResponse> booking;
        try {
            assertThat(reduced.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            booking = executor.submit(book);

            awaitSessionWaitingOnGearLock();
            assertThat(booking.isDone()).as("the booking must wait for the stock update's gear lock").isFalse();
        } finally {
            releaseReduction.countDown();
        }

        result(reduce);
        assertThatThrownBy(() -> result(booking))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo("Only 0 x " + tent.getName() + " available right now");
                });
        assertThat(storedStock(tent)).isZero();
        assertThat(reserved(tent)).isZero();
    }

    /**
     * Proves the order: the higher-ID item is held by another transaction and the booking asks for it first.
     * Locking in request order would wait on it holding nothing; locking in ID order takes the lower-ID item
     * first and holds it while waiting.
     */
    @Test
    void gearIsLockedInAscendingIdOrderNotRequestOrder() throws Exception {
        GearItem one = saveGear(1);
        GearItem two = saveGear(1);
        GearItem lower = one.getGearItemId().compareTo(two.getGearItemId()) < 0 ? one : two;
        GearItem higher = lower == one ? two : one;
        Callable<BookingResponse> higherFirst = booking(higher, lower);
        CountDownLatch higherLocked = new CountDownLatch(1);
        CountDownLatch releaseHigher = new CountDownLatch(1);

        Future<?> holder = executor.submit(() -> transaction.executeWithoutResult(status -> {
            gearService.findForBooking(higher.getGearItemId());
            higherLocked.countDown();
            try {
                releaseHigher.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        Future<BookingResponse> booking;
        try {
            assertThat(higherLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            booking = executor.submit(higherFirst);

            awaitSessionWaitingOnGearLock();
            assertThat(isLockedByAnotherTransaction(lower.getGearItemId()))
                    .as("the booking must already hold the lower-ID item while waiting for the higher one")
                    .isTrue();
        } finally {
            releaseHigher.countDown();
        }

        result(holder);
        assertThat(result(booking).gear()).hasSize(2);
        assertThat(reserved(lower)).isEqualTo(1);
        assertThat(reserved(higher)).isEqualTo(1);
    }
}
