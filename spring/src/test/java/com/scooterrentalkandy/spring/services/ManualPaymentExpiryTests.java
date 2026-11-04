package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The payment hold for QR payments and bank transfers (BE-045). Committed fixtures: the expiry runs each booking in
 * its own transaction. A receipt waiting for verification keeps the booking beyond the 30-minute payment hold, but
 * only for the verification hold (120 minutes from submission by default); a rejected receipt starts the 30-minute
 * hold again; without a receipt the usual hold applies.
 */
class ManualPaymentExpiryTests extends PostgresIntegrationTest {

    @Autowired
    private BookingService bookingService;

    @Autowired
    private ManualPaymentService transfers;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentReceiptRepository receipts;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdScooters = new ArrayList<>();
    private User customer;
    private User admin;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        customer = saveUser(Role.USER);
        admin = saveUser(Role.ADMIN);
        now = LocalDateTime.now();
    }

    @AfterEach
    void deleteFixtures() {
        for (UUID user : createdUsers) {
            String paymentsOf = "SELECT payment_id FROM payments WHERE booking_id IN "
                    + "(SELECT booking_id FROM bookings WHERE user_id = ?)";
            jdbc.update("DELETE FROM payment_receipts WHERE payment_id IN (" + paymentsOf + ")", user);
            jdbc.update("DELETE FROM payment_transactions WHERE payment_id IN (" + paymentsOf + ")", user);
            jdbc.update("DELETE FROM payments WHERE booking_id IN (SELECT booking_id FROM bookings WHERE user_id = ?)",
                    user);
            jdbc.update("DELETE FROM bookings WHERE user_id = ?", user);
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", user);
        }
        createdScooters.forEach(id -> jdbc.update("DELETE FROM scooters WHERE scooter_id = ?", id));
        createdUsers.forEach(id -> jdbc.update("DELETE FROM users WHERE user_id = ?", id));
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("hold." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Hold Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("hold" + id));
        u.setRole(role);
        u.setIsActive(true);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    /** A signed PENDING booking created {@code minutesAgo} minutes ago, to be paid by bank transfer. */
    private Booking transferBooking(long minutesAgo) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("H-" + id);
        s.setModel("Hold Dio");
        s.setPlateNumber("HP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s = scooters.saveAndFlush(s);
        createdScooters.add(s.getScooterId());

        Booking b = new Booking();
        b.setReference("BK-H" + id);
        b.setUser(customer);
        b.setScooter(s);
        b.setStartTime(now.plusDays(1));
        b.setStatus(BookingStatus.PENDING);
        b.setPickupLocation("Kandy");
        b.setContractSigned(true);
        b.setCreatedAt(now.minusMinutes(minutesAgo));
        b = bookings.saveAndFlush(b);

        Payment p = new Payment();
        p.setBooking(b);
        p.setMethod(PaymentMethod.BANK_TRANSFER);
        p.setStatus(PaymentStatus.PENDING);
        p.setAmount(new BigDecimal("250.00"));
        payments.saveAndFlush(p);
        return b;
    }

    private PaymentReceipt receipt(Booking b, PaymentReceipt.Status status, long submittedMinutesAgo,
                                   Long reviewedMinutesAgo) {
        PaymentReceipt r = new PaymentReceipt();
        r.setPayment(payments.findByBooking_BookingId(b.getBookingId()).orElseThrow());
        r.setKind(PaymentReceipt.Kind.INITIAL);
        r.setMethod(PaymentMethod.BANK_TRANSFER);
        r.setAmount(new BigDecimal("250.00"));
        r.setStorageKey(UUID.randomUUID() + ".png");
        r.setContentType("image/png");
        r.setSizeBytes(100);
        r.setStatus(status);
        r.setSubmittedAt(now.minusMinutes(submittedMinutesAgo));
        if (reviewedMinutesAgo != null) {
            r.setReviewedAt(now.minusMinutes(reviewedMinutesAgo));
            r.setReviewedBy(admin);
            r.setRejectionReason("Unreadable receipt");
        }
        return receipts.saveAndFlush(r);
    }

    private BookingStatus statusOf(Booking b) {
        return bookings.findById(b.getBookingId()).orElseThrow().getStatus();
    }

    private long notifications(User user, String subjectPrefix) {
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND subject LIKE ?",
                Long.class, user.getUserId(), subjectPrefix + "%");
    }

    @Test
    void aReceiptWaitingForVerificationKeepsTheBookingBeyondThePaymentHold() {
        Booking b = transferBooking(45);
        receipt(b, PaymentReceipt.Status.PENDING_VERIFICATION, 40, null);

        bookingService.expireUnpaid(now.minusMinutes(30));

        assertThat(statusOf(b)).isEqualTo(BookingStatus.PENDING);
        assertThat(receipts.findPendingByBookingId(b.getBookingId())).isPresent();
    }

    @Test
    void anUnverifiedReceiptReleasesTheBookingAfterTheVerificationHold() {
        Booking b = transferBooking(200);
        PaymentReceipt r = receipt(b, PaymentReceipt.Status.PENDING_VERIFICATION, 121, null);

        bookingService.expireUnpaid(now.minusMinutes(30));

        assertThat(statusOf(b)).isEqualTo(BookingStatus.CANCELLED);
        assertThat(receipts.findById(r.getPaymentReceiptId()).orElseThrow().getStatus())
                .isEqualTo(PaymentReceipt.Status.EXPIRED);
        Payment p = payments.findByBooking_BookingId(b.getBookingId()).orElseThrow();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("Booking cancelled before the transfer was verified");
        assertThat(bookings.existsByScooter_ScooterIdAndStatusIn(b.getScooter().getScooterId(), BookingStatus.BLOCKING))
                .isFalse();
        assertThat(notifications(customer, "Booking released")).isEqualTo(1);
        assertThat(notifications(admin, "Unverified receipt")).isEqualTo(1);

        // The expired receipt can no longer be approved: the booking was released.
        AuthUser approver = new AuthUser(admin.getUserId(), admin.getEmail(), Role.ADMIN);
        assertThatThrownBy(() -> transfers.approve(r.getPaymentReceiptId(), new BigDecimal("250"), approver))
                .isInstanceOf(ApiException.class).hasMessageContaining("released");
    }

    @Test
    void aRejectedReceiptStartsThePaymentHoldAgain() {
        Booking recent = transferBooking(45);
        receipt(recent, PaymentReceipt.Status.REJECTED, 40, 10L);
        Booking stale = transferBooking(90);
        receipt(stale, PaymentReceipt.Status.REJECTED, 85, 40L);

        bookingService.expireUnpaid(now.minusMinutes(30));

        assertThat(statusOf(recent)).isEqualTo(BookingStatus.PENDING);
        assertThat(statusOf(stale)).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void withoutAReceiptTheUsualPaymentHoldApplies() {
        Booking b = transferBooking(31);

        bookingService.expireUnpaid(now.minusMinutes(30));

        assertThat(statusOf(b)).isEqualTo(BookingStatus.CANCELLED);
        assertThat(payments.findByBooking_BookingId(b.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }
}
