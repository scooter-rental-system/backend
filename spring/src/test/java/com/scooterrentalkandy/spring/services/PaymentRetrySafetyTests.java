package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Provider side effects against committed transactions on the embedded PostgreSQL. When the provider acts but
 * its answer is lost, our transaction rolls back and the retry repeats the same idempotency key, so the provider
 * (here the test gateway, which replays keys like Stripe) moves money once. Webhooks lock the booking before the
 * payment, like expiry and cancellation, so they wait instead of deadlocking; waiting is proven with PostgreSQL's
 * lock state under a deadline, no fixed sleeps. Fixtures are committed and deleted after each test.
 */
class PaymentRetrySafetyTests extends PostgresIntegrationTest {

    private static final long TIMEOUT_SECONDS = 20;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private TestPaymentGateway gateway;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentTransactionRepository transactions;

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
        gateway.reset();
        executor = Executors.newFixedThreadPool(2);
        transaction = new TransactionTemplate(transactionManager);
    }

    @AfterEach
    void stopAndDeleteFixtures() throws InterruptedException {
        executor.shutdownNow();
        assertThat(executor.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        jdbc.update("DELETE FROM payment_provider_events WHERE event_id LIKE 'evt_test_%'");
        for (UUID user : createdUsers) {
            String bookingsOf = "SELECT booking_id FROM bookings WHERE user_id = ?";
            jdbc.update("DELETE FROM payment_transactions WHERE payment_id IN "
                    + "(SELECT payment_id FROM payments WHERE booking_id IN (" + bookingsOf + "))", user);
            jdbc.update("DELETE FROM payments WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM invoice_lines WHERE invoice_id IN "
                    + "(SELECT invoice_id FROM invoices WHERE booking_id IN (" + bookingsOf + "))", user);
            jdbc.update("DELETE FROM invoices WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM gps_pings WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        createdScooters.forEach(id -> jdbc.update("DELETE FROM maintenance_records WHERE scooter_id = ?", id));
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    // ── Fixtures (committed) ──────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveCustomer() {
        String id = unique();
        User u = new User();
        u.setEmail("retry." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Retry Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("T" + id);
        u.setRole(Role.USER);
        u.setStripeCustomerId("cus_test_" + id);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Booking saveBooking(BookingStatus status, LocalDateTime start, ScooterStatus scooterStatus) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RT-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("RTP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setStatus(scooterStatus);
        scooters.saveAndFlush(s);
        createdScooters.add(s.getScooterId());
        Booking b = new Booking();
        b.setReference("BK-T" + id);
        b.setUser(saveCustomer());
        b.setScooter(s);
        b.setStatus(status);
        b.setStartTime(start);
        b.setContractSigned(true);
        b.setPickupLocation("Test pickup");
        b.setCreatedAt(LocalDateTime.now().minusHours(2));
        return bookings.saveAndFlush(b);
    }

    private Payment savePayment(Booking b, PaymentStatus status) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setAmount(new BigDecimal("250.00"));
        p.setMethod(PaymentMethod.CARD);
        p.setStatus(status);
        p.setProvider("test");
        p.setStripePaymentId("pi_test_" + unique());
        if (status == PaymentStatus.SUCCESS) {
            p.setPaymentMethodRef("pm_test_saved");
            p.setPaidAt(LocalDateTime.now().minusHours(2));
        }
        payments.saveAndFlush(p);
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(PaymentTransaction.Kind.INITIAL);
        t.setProviderRef(p.getStripePaymentId());
        t.setAmount(p.getAmount());
        t.setStatus(status == PaymentStatus.SUCCESS ? PaymentTransaction.Status.SUCCESS
                : PaymentTransaction.Status.PENDING);
        t.setCreatedAt(LocalDateTime.now().minusHours(2));
        transactions.saveAndFlush(t);
        return p;
    }

    /** Paid (ACTIVE) booking whose rental starts at {@code start}. */
    private Booking paidBooking(LocalDateTime start) {
        Booking b = saveBooking(BookingStatus.ACTIVE, start, ScooterStatus.RENTED);
        savePayment(b, PaymentStatus.SUCCESS);
        return b;
    }

    private static AuthUser auth(Booking b) {
        User u = b.getUser();
        return new AuthUser(u.getUserId(), u.getEmail(), u.getRole());
    }

    private Booking reload(Booking b) {
        return bookings.findById(b.getBookingId()).orElseThrow();
    }

    private Payment paymentOf(Booking b) {
        return payments.findByBooking_BookingId(b.getBookingId()).orElseThrow();
    }

    private long transactionsOf(Booking b, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM payment_transactions t JOIN payments p "
                + "ON p.payment_id = t.payment_id WHERE p.booking_id = ? AND t.kind = ?", Long.class,
                b.getBookingId(), kind);
    }

    private long invoicesOf(Booking b) {
        return jdbc.queryForObject("SELECT count(*) FROM invoices WHERE booking_id = ?", Long.class,
                b.getBookingId());
    }

    private void awaitBlockedOnBookingLock() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND wait_event_type = 'Lock' AND query ILIKE '%bookings%'", Long.class) == 0) {
            if (System.nanoTime() > deadline) {
                fail("No session was blocked on a booking row lock within " + TIMEOUT_SECONDS + " s");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
    }

    // ── Lost provider answers ─────────────────────────────────────────────────

    @Test
    void aCancellationRefundWhoseAnswerIsLostIsNotRepeated() {
        Booking b = paidBooking(LocalDateTime.now().plusDays(2));
        String intentId = paymentOf(b).getStripePaymentId();
        gateway.loseRefundResponses(new PaymentProviderException("Connection reset", null));

        assertThatThrownBy(() -> bookingService.cancel(b.getBookingId(), auth(b)))
                .isInstanceOf(PaymentProviderException.class);

        // Our side rolled back; the provider did refund.
        assertThat(reload(b).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(paymentOf(b).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(transactionsOf(b, "REFUND")).isZero();
        assertThat(gateway.refunds()).hasSize(1);

        gateway.loseRefundResponses(null);
        bookingService.cancel(b.getBookingId(), auth(b));

        assertThat(reload(b).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(paymentOf(b).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gateway.refundCalls()).isEqualTo(2);
        assertThat(gateway.refunds()).hasSize(1);
        assertThat(gateway.refunds().getFirst().idempotencyKey()).isEqualTo("refund-" + intentId + "-full");
        assertThat(transactionsOf(b, "REFUND")).isEqualTo(1);
    }

    @Test
    void aBalanceChargeWhoseAnswerIsLostIsNotRepeatedAndTheTotalIsKept() {
        // Started 2.5 hours ago without GPS points: 3 hours x 250.00 = 750.00, of which 250.00 is paid.
        Booking b = paidBooking(LocalDateTime.now().minusMinutes(150));
        gateway.loseChargeResponses(new PaymentProviderException("Read timed out", null));

        assertThatThrownBy(() -> bookingService.complete(b.getBookingId(), auth(b)))
                .isInstanceOf(PaymentProviderException.class);

        // The settlement and invoice were committed before any money moved; the charge transaction rolled back.
        Booking ended = reload(b);
        assertThat(ended.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(ended.getEndedAt()).isNotNull();
        assertThat(ended.getPendingTotalCost()).isEqualByComparingTo("750.00");
        assertThat(invoicesOf(b)).isEqualTo(1);
        assertThat(paymentOf(b).getAmount()).isEqualByComparingTo("250.00");
        assertThat(transactionsOf(b, "BALANCE")).isZero();
        assertThat(gateway.charges()).hasSize(1);

        gateway.loseChargeResponses(null);
        var completed = bookingService.complete(b.getBookingId(), auth(b));

        assertThat(completed.status()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(gateway.charges()).hasSize(1);
        assertThat(gateway.charges().getFirst().amount()).isEqualByComparingTo("500.00");
        assertThat(gateway.charges().getFirst().idempotencyKey())
                .isEqualTo("booking-" + b.getBookingId() + "-balance-charge-1");
        assertThat(paymentOf(b).getAmount()).isEqualByComparingTo("750.00");
        assertThat(reload(b).getEndTime()).isEqualTo(ended.getEndedAt());
        assertThat(invoicesOf(b)).isEqualTo(1);
        assertThat(transactionsOf(b, "BALANCE")).isEqualTo(1);
    }

    // ── Lock order ────────────────────────────────────────────────────────────

    /**
     * Unpaid-booking expiry (as UnpaidBookingExpiry does it: booking lock, cancel, close the intent under the
     * payment lock) runs while the payment succeeds. The webhook waits for the booking lock, then sees the
     * cancelled booking and refunds the late payment. With the old order (payment row first) the two transactions
     * deadlocked.
     */
    @Test
    void aWebhookWaitsForExpiryAndRefundsTheLatePayment() throws Exception {
        Booking b = saveBooking(BookingStatus.PENDING, LocalDateTime.now().plusDays(1), ScooterStatus.AVAILABLE);
        Payment open = savePayment(b, PaymentStatus.PENDING);
        String payload = gateway.deliver(gateway.succeeded(open.getStripePaymentId(), open.getAmount(), "LKR",
                Map.of("bookingId", b.getBookingId().toString(), "purpose", "INITIAL"), "pm_test_saved", "4242"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> expiry = executor.submit(() -> transaction.executeWithoutResult(status -> {
            Booking held = bookings.findByIdForUpdate(b.getBookingId()).orElseThrow();
            locked.countDown();
            try {
                assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            held.setStatus(BookingStatus.CANCELLED);
            held.setCancelledAt(LocalDateTime.now());
            paymentService.cancelOpenIntent(held);
        }));
        assertThat(locked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
        Future<?> webhook = executor.submit(() -> paymentService.handleWebhook(payload, TestPaymentGateway.SIGNATURE));

        awaitBlockedOnBookingLock();
        assertThat(webhook.isDone()).isFalse();
        release.countDown();
        expiry.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        webhook.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertThat(reload(b).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(scooters.findById(b.getScooter().getScooterId()).orElseThrow().getStatus())
                .isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(paymentOf(b).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(gateway.refunds()).hasSize(1);
        assertThat(gateway.refunds().getFirst().intentId()).isEqualTo(open.getStripePaymentId());
    }

    @Test
    void theSameEventDeliveredTwiceAtOnceIsProcessedOnce() throws Exception {
        Booking b = saveBooking(BookingStatus.PENDING, LocalDateTime.now().plusDays(1), ScooterStatus.AVAILABLE);
        Payment open = savePayment(b, PaymentStatus.PENDING);
        TestPaymentGateway.Event success = gateway.succeeded(open.getStripePaymentId(), open.getAmount(), "LKR",
                Map.of("bookingId", b.getBookingId().toString(), "purpose", "INITIAL"), "pm_test_saved", "4242");
        String payload = gateway.deliver(success);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<?>> deliveries = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            deliveries.add(executor.submit(() -> {
                assertThat(go.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
                paymentService.handleWebhook(payload, TestPaymentGateway.SIGNATURE);
                return null;
            }));
        }
        go.countDown();
        for (Future<?> delivery : deliveries) {
            delivery.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        assertThat(reload(b).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_provider_events WHERE event_id = ?", Long.class,
                success.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND channel = 'IN_APP'",
                Long.class, b.getUser().getUserId())).isEqualTo(1);
    }
}
