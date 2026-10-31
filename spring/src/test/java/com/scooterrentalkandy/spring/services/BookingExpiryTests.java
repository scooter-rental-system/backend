package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.CreateBookingRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearSelectionRequest;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.Rentals;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unpaid-booking expiry against the embedded PostgreSQL. Each booking is expired in its own transaction, which
 * cannot see a test transaction's uncommitted rows, so this class commits its fixtures and deletes everything it
 * created after each test. Cutoffs are explicit; nothing waits for the 30-minute hold and no scheduler runs.
 *
 * <p>The payment gateway is the test-only {@link TestPaymentGateway}, so no payment provider is called.
 */
class BookingExpiryTests extends PostgresIntegrationTest {

    /** Candidates must have been created before this: well after the stale fixtures, well before the fresh ones. */
    private final LocalDateTime cutoff = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);

    @Autowired
    private TestPaymentGateway gateway;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private UnpaidBookingExpiry unpaidExpiry;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentTransactionRepository paymentTransactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();
    private final List<UUID> createdGear = new ArrayList<>();

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    @AfterEach
    void deleteFixtures() {
        for (UUID user : createdUsers) {
            String bookingsOf = "SELECT booking_id FROM bookings WHERE user_id = ?";
            jdbc.update("DELETE FROM payment_transactions WHERE payment_id IN "
                    + "(SELECT payment_id FROM payments WHERE booking_id IN (" + bookingsOf + "))", user);
            jdbc.update("DELETE FROM payments WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM contracts WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM booking_gear WHERE booking_id IN (" + bookingsOf + ")", user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
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
        u.setEmail("expiry." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Expiry Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("E" + id);
        u.setRole(Role.USER);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("E-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("EP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Scooter saved = scooters.saveAndFlush(s);
        createdScooters.add(saved.getScooterId());
        return saved;
    }

    private GearItem saveGear(int quantity) {
        GearItem g = new GearItem();
        g.setName("Test Tent " + unique());
        g.setCategory(Category.CAMPING);
        g.setDailyRate(new BigDecimal("500.00"));
        g.setTotalQuantity(quantity);
        GearItem saved = gearItems.saveAndFlush(g);
        createdGear.add(saved.getGearItemId());
        return saved;
    }

    private Booking saveBooking(BookingStatus status, LocalDateTime createdAt, Scooter scooter, GearItem gear) {
        Booking b = new Booking();
        b.setReference("BK-E" + unique());
        b.setUser(saveUser());
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(LocalDateTime.now().plusDays(1));
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setPickupLocation("Test pickup");
        b.setCreatedAt(createdAt);
        if (status == BookingStatus.COMPLETED) {
            // ck_bookings_final_fields: a completed booking carries its settlement.
            b.setEndTime(LocalDateTime.now().minusHours(1));
            b.setDistanceKm(new BigDecimal("12.00"));
            b.setTotalCost(new BigDecimal("680.00"));
            b.setContractSigned(true);
        }
        if (gear != null) {
            BookingGear line = new BookingGear();
            line.setGearItem(gear);
            line.setQuantity(1);
            b.addGear(line);
        }
        return bookings.saveAndFlush(b);
    }

    private Booking stalePending() {
        return saveBooking(BookingStatus.PENDING, cutoff.minusHours(2), saveScooter(), null);
    }

    /** An open (PENDING) initial payment with its PENDING transaction, as created by a payment intent. */
    private Payment openPayment(Booking b, String intentId) {
        Payment p = new Payment();
        p.setBooking(b);
        p.setAmount(new BigDecimal("250.00"));
        p.setMethod(PaymentMethod.CARD);
        p.setStatus(PaymentStatus.PENDING);
        p.setProvider("test");
        p.setStripePaymentId(intentId);
        Payment saved = payments.saveAndFlush(p);
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(saved);
        t.setKind(PaymentTransaction.Kind.INITIAL);
        t.setProviderRef(intentId);
        t.setAmount(saved.getAmount());
        t.setStatus(PaymentTransaction.Status.PENDING);
        t.setCreatedAt(LocalDateTime.now());
        paymentTransactions.saveAndFlush(t);
        return saved;
    }

    private Booking reload(Booking b) {
        return bookings.findById(b.getBookingId()).orElseThrow();
    }

    private long notificationsFor(Booking b) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ?", Long.class,
                b.getUser().getUserId());
    }

    // ── Expiry ────────────────────────────────────────────────────────────────

    @Test
    void stalePendingBookingWithoutPaymentIsCancelled() {
        Booking stale = stalePending();

        assertThat(bookingService.expireUnpaid(cutoff)).isEqualTo(1);

        Booking after = reload(stale);
        assertThat(after.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(after.getCancelledAt()).isNotNull();
        assertThat(payments.findByBooking_BookingId(stale.getBookingId())).isEmpty();
        assertThat(gateway.cancelled()).isEmpty();
        assertThat(notificationsFor(stale)).isZero();
    }

    @Test
    void expiryFreesTheScooterAndGearForTheNextCustomer() {
        Scooter scooter = saveScooter();
        GearItem onlyTent = saveGear(1);
        saveBooking(BookingStatus.PENDING, cutoff.minusHours(2), scooter, onlyTent);
        User next = saveUser();
        CreateBookingRequest sameScooterAndTent = new CreateBookingRequest(scooter.getScooterId(),
                LocalDateTime.now().plusDays(2), List.of(new GearSelectionRequest(onlyTent.getGearItemId(), 1)),
                "Kandy Lake Round", null);

        bookingService.expireUnpaid(cutoff);

        var created = bookingService.create(next.getUserId(), sameScooterAndTent);
        assertThat(created.status()).isEqualTo(BookingStatus.PENDING);
        assertThat(created.gear()).hasSize(1);
    }

    @Test
    void pendingBookingNewerThanTheCutoffIsKept() {
        Booking fresh = saveBooking(BookingStatus.PENDING, cutoff.plusMinutes(25), saveScooter(), null);

        assertThat(bookingService.expireUnpaid(cutoff)).isZero();

        assertThat(reload(fresh).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(reload(fresh).getCancelledAt()).isNull();
    }

    @Test
    void readyActiveCancelledAndCompletedBookingsAreNotTouched() {
        LocalDateTime old = cutoff.minusHours(5);
        Booking ready = saveBooking(BookingStatus.READY_FOR_PICKUP, old, saveScooter(), null);
        Booking active = saveBooking(BookingStatus.ACTIVE, old, saveScooter(), null);
        Booking cancelled = saveBooking(BookingStatus.CANCELLED, old, saveScooter(), null);
        Booking completed = saveBooking(BookingStatus.COMPLETED, old, saveScooter(), null);

        assertThat(bookingService.expireUnpaid(cutoff)).isZero();

        assertThat(reload(ready).getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(reload(active).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(reload(cancelled).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reload(cancelled).getCancelledAt()).isNull();
        assertThat(reload(completed).getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(gateway.cancelled()).isEmpty();
    }

    @Test
    void openPaymentIntentIsCancelledOnceAndMarkedFailed() {
        Booking stale = stalePending();
        Payment payment = openPayment(stale, "pi_test_open_" + unique());

        assertThat(bookingService.expireUnpaid(cutoff)).isEqualTo(1);

        assertThat(gateway.cancelled()).containsExactly(payment.getStripePaymentId());
        Payment after = payments.findById(payment.getPaymentId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(after.getFailureReason()).isEqualTo("Booking cancelled before payment was completed");
        assertThat(paymentTransactions.findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(
                payment.getPaymentId(), payment.getStripePaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentTransaction.Status.FAILED);
        assertThat(reload(stale).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(notificationsFor(stale)).isZero();
    }

    @Test
    void aSecondRunChangesNothing() {
        Booking stale = stalePending();
        Payment payment = openPayment(stale, "pi_test_twice_" + unique());
        bookingService.expireUnpaid(cutoff);
        LocalDateTime cancelledAt = reload(stale).getCancelledAt();

        assertThat(bookingService.expireUnpaid(cutoff)).isZero();

        assertThat(reload(stale).getCancelledAt()).isEqualTo(cancelledAt);
        assertThat(payments.findById(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(gateway.cancelled()).hasSize(1);
    }

    // ── Race protection: state is re-checked under the row lock ───────────────

    /**
     * Deterministic stand-in for the payment race: the booking is selected as a candidate while PENDING, then a
     * (committed) payment activates it before it is processed. The locked re-check must skip it.
     */
    @Test
    void candidateActivatedAfterSelectionIsSkipped() {
        Booking stale = stalePending();
        Payment payment = openPayment(stale, "pi_test_raced_" + unique());
        List<UUID> candidates = bookings.findIdsByStatusAndCreatedAtBefore(BookingStatus.PENDING, cutoff);
        assertThat(candidates).contains(stale.getBookingId());

        Booking paid = reload(stale);
        paid.setStatus(BookingStatus.READY_FOR_PICKUP);
        bookings.saveAndFlush(paid);
        Payment succeeded = payments.findById(payment.getPaymentId()).orElseThrow();
        succeeded.setStatus(PaymentStatus.SUCCESS);
        payments.saveAndFlush(succeeded);

        assertThat(unpaidExpiry.expire(stale.getBookingId(), cutoff)).isFalse();

        Booking after = reload(stale);
        assertThat(after.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(after.getCancelledAt()).isNull();
        assertThat(payments.findById(payment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.SUCCESS);
        assertThat(gateway.cancelled()).isEmpty();
    }

    @Test
    void candidateCancelledOrMissingAfterSelectionIsSkipped() {
        Booking stale = stalePending();
        Booking byCustomer = reload(stale);
        byCustomer.setStatus(BookingStatus.CANCELLED);
        LocalDateTime customerCancelledAt = LocalDateTime.now().minusMinutes(1).truncatedTo(ChronoUnit.SECONDS);
        byCustomer.setCancelledAt(customerCancelledAt);
        bookings.saveAndFlush(byCustomer);

        assertThat(unpaidExpiry.expire(stale.getBookingId(), cutoff)).isFalse();
        assertThat(unpaidExpiry.expire(UUID.randomUUID(), cutoff)).isFalse();

        assertThat(reload(stale).getCancelledAt()).isEqualTo(customerCancelledAt);
    }

    @Test
    void candidateThatNoLongerQualifiesForTheCutoffIsSkipped() {
        Booking stale = stalePending();

        assertThat(unpaidExpiry.expire(stale.getBookingId(), stale.getCreatedAt().minusMinutes(1))).isFalse();

        assertThat(reload(stale).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    // ── Failure isolation ─────────────────────────────────────────────────────

    @Test
    void oneFailingBookingDoesNotStopTheOthers() {
        Booking withoutPayment = stalePending();
        Booking failing = stalePending();
        Booking withPayment = stalePending();
        Payment failingPayment = openPayment(failing, "pi_test_fail_" + unique());
        Payment okPayment = openPayment(withPayment, "pi_test_ok_" + unique());
        gateway.failCancel(failingPayment.getStripePaymentId(),
                new IllegalStateException("payment provider unavailable"));

        assertThat(bookingService.expireUnpaid(cutoff)).isEqualTo(2);

        assertThat(reload(withoutPayment).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(reload(withPayment).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(payments.findById(okPayment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        // The failing booking's own transaction rolled back: still PENDING, retried on the next run.
        assertThat(reload(failing).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(reload(failing).getCancelledAt()).isNull();
        assertThat(payments.findById(failingPayment.getPaymentId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.PENDING);
    }
}
