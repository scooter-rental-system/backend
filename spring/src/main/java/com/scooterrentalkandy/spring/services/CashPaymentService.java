package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PaymentEvents.BalancePaid;
import com.scooterrentalkandy.spring.services.PaymentEvents.CashRefunded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash at the rental counter (BE-044). Cash is never a checkout option: the customer pays the initial charge online
 * (card, QR payment or bank transfer), and only an admin records cash, when the customer hands it over at the counter.
 * That is the final balance of a rental first paid by QR payment or bank transfer, and cash handed back. Each amount an
 * admin records is a PaymentTransaction with method CASH naming the admin and no provider reference, next to the
 * earlier transactions, which keep their own method. Nothing here calls a payment provider. Bookings paid in cash
 * before cash left the checkout (Payment.method CASH) are still settled and refunded here.
 *
 * <p>The booking lifecycle is the one card payments use: recording the final balance publishes {@link BalancePaid}
 * and recording a refund owed at the end publishes {@link CashRefunded} (the rental completes).
 *
 * <p>The settlement at the end of a rental ({@link #settleAtEnd}) and the refund owed after a cancellation serve
 * every manual method (cash, QR payment, bank transfer): the balance or refund due is kept on the payment.
 *
 * <p>The amount recorded is always the balance the server holds; the admin's request names the amount they saw and any
 * other amount is refused, since the screen was out of date. Repeating a confirmation that is already recorded changes
 * nothing. Lock order as in {@link PaymentService}:
 * the booking row, then the payment row.
 */
@Service
public class CashPaymentService {

    private static final Logger log = LoggerFactory.getLogger(CashPaymentService.class);

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentReceiptRepository receipts;
    private final UserRepository users;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public CashPaymentService(BookingRepository bookings, PaymentRepository payments,
                              PaymentTransactionRepository transactions, PaymentReceiptRepository receipts,
                              UserRepository users, NotificationService notifications,
                              ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.payments = payments;
        this.transactions = transactions;
        this.receipts = receipts;
        this.users = users;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    // ---------------------------------------------------------------- admin confirmations

    /**
     * An admin records the remaining balance as received in cash at the counter; the rental completes. For a booking
     * first paid by QR payment or bank transfer (or in cash before cash left the checkout), once the final bill is
     * fixed and only while no transfer receipt for the balance is waiting. The amount recorded is the server's balance.
     */
    @Transactional
    public void confirmBalance(UUID bookingId, BigDecimal amount, AuthUser admin) {
        Booking b = lockBooking(bookingId);
        Payment p = manualPayment(bookingId);
        if (p.getBalanceDue() == null && b.getStatus() == BookingStatus.COMPLETED && cashBalanceRecorded(p)) {
            return; // already recorded
        }
        if (b.getStatus() != BookingStatus.ACTIVE || b.getEndedAt() == null || p.getBalanceDue() == null) {
            throw ApiException.conflict("No cash balance is due on this booking");
        }
        requireNoPendingReceipt(bookingId);
        BigDecimal due = p.getBalanceDue();
        requireAmount(amount, due);
        record(p, PaymentTransaction.Kind.BALANCE, due, admin);
        p.setAmount(p.getAmount().add(due));
        p.setBalanceDue(null);
        p.setPaidAt(now());
        p.setFailureReason(null);
        log.info("Cash balance {} for booking {} recorded by admin {}", due, b.getReference(), admin.id());
        notifications.notify(b.getUser(), "Final cash payment received: " + b.getReference(),
                "Your final cash payment of " + money(due) + " was received. Thank you.");
        notifications.notifyAdmins("Final cash payment received " + b.getReference(),
                "The final cash balance of " + money(due) + " for " + b.getReference() + " was recorded.");
        events.publishEvent(new BalancePaid(bookingId));
    }

    /**
     * An admin records that the cash owed back was handed to the customer: at the end of a rental whose final cost
     * is below the cash received (the rental then completes), or after a paid booking was cancelled.
     */
    @Transactional
    public void confirmRefund(UUID bookingId, BigDecimal amount, AuthUser admin) {
        Booking b = lockBooking(bookingId);
        Payment p = cashPayment(bookingId);
        if (p.getRefundDue() == null) {
            if (recorded(p, PaymentTransaction.Kind.REFUND)) {
                return; // already recorded
            }
            throw ApiException.conflict("No cash refund is due on this booking");
        }
        BigDecimal refund = p.getRefundDue();
        requireAmount(amount, refund);
        record(p, PaymentTransaction.Kind.REFUND, refund, admin);
        if (refund.compareTo(p.getAmount()) >= 0) {
            p.setStatus(PaymentStatus.REFUNDED); // everything received was handed back; the amount stays as paid
        } else {
            p.setAmount(p.getAmount().subtract(refund));
        }
        p.setRefundDue(null);
        log.info("Cash refund {} for booking {} recorded by admin {}", refund, b.getReference(), admin.id());
        notifications.notify(b.getUser(), "Cash refund recorded: " + b.getReference(),
                "A cash refund of " + money(refund) + " was recorded for " + b.getReference() + ".");
        events.publishEvent(new CashRefunded(bookingId));
    }

    // ---------------------------------------------------------------- booking lifecycle

    /**
     * The rental has ended and its final total is fixed: what is left to settle at the counter or by transfer,
     * instead of charging a card. The balance due or the refund due is recorded on the payment for an admin to
     * confirm. Returns true when the amount received matches the final total exactly and the rental can complete now.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean settleAtEnd(Booking b) {
        Payment p = manualPayment(b.getBookingId());
        if (p.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Cash payment for booking " + b.getBookingId() + " is " + p.getStatus());
        }
        BigDecimal remaining = b.getPendingTotalCost().subtract(p.getAmount());
        boolean announced = p.getBalanceDue() != null || p.getRefundDue() != null;
        p.setBalanceDue(remaining.signum() > 0 ? remaining : null);
        p.setRefundDue(remaining.signum() < 0 ? remaining.negate() : null);
        if (remaining.signum() == 0) {
            return true;
        }
        if (!announced && p.getMethod().isTransfer()) {
            announceTransferSettlement(b, p, remaining);
        } else if (!announced) {
            String bill = "Your rental has ended. Final total " + money(b.getPendingTotalCost()) + "; cash received "
                    + money(p.getAmount()) + ". ";
            if (remaining.signum() > 0) {
                notifications.notify(b.getUser(), "Cash balance due: " + b.getReference(), bill
                        + "Please pay the cash balance of " + money(remaining) + " at the rental counter.");
                notifications.notifyAdmins("Cash balance due " + b.getReference(), "Collect " + money(remaining)
                        + " in cash for " + b.getReference() + " and confirm it on the booking.");
            } else {
                notifications.notify(b.getUser(), "Cash refund due: " + b.getReference(), bill
                        + "A cash refund of " + money(remaining.negate()) + " is due to you at the rental counter.");
                notifications.notifyAdmins("Cash refund due " + b.getReference(), "Hand back "
                        + money(remaining.negate()) + " in cash for " + b.getReference()
                        + " and confirm it on the booking.");
            }
        }
        return false;
    }

    /** The settlement of a rental first paid by QR payment or bank transfer, as the customer and admins see it. */
    private void announceTransferSettlement(Booking b, Payment p, BigDecimal remaining) {
        String bill = "Your rental has ended. Adjusted final total " + money(b.getPendingTotalCost())
                + "; already received " + money(p.getAmount()) + ". ";
        if (remaining.signum() > 0) {
            notifications.notify(b.getUser(), "Balance due: " + b.getReference(), bill + "Please transfer the "
                    + "balance of " + money(remaining) + " by QR payment or bank transfer and upload the receipt on "
                    + "your booking, or pay it in cash at the rental counter.");
            notifications.notifyAdmins("Balance due " + b.getReference(), money(remaining) + " is still due for "
                    + b.getReference() + ". Approve the customer's receipt under Payments once the money arrives, "
                    + "or confirm cash received at the counter.");
        } else {
            notifications.notify(b.getUser(), "Refund due: " + b.getReference(), bill + "A refund of "
                    + money(remaining.negate()) + " is due to you; our team will send it and let you know.");
            notifications.notifyAdmins("Refund due " + b.getReference(), "Refund " + money(remaining.negate())
                    + " to the customer for " + b.getReference() + " from the business account, then confirm the "
                    + "manual refund on the booking.");
        }
    }

    /** A booking paid at the counter or by transfer was cancelled before it started: all of it is owed back. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment refundDueOnCancellation(Booking b) {
        Payment p = manualPayment(b.getBookingId());
        if (p.getStatus() == PaymentStatus.SUCCESS) {
            p.setRefundDue(p.getAmount());
            boolean inCash = p.getMethod() == PaymentMethod.CASH;
            notifications.notifyAdmins((inCash ? "Cash refund due " : "Refund due ") + b.getReference(), inCash
                    ? "Hand back " + money(p.getAmount()) + " in cash for the cancelled booking " + b.getReference()
                            + " and confirm it on the booking."
                    : "Refund " + money(p.getAmount()) + " to the customer for the cancelled booking "
                            + b.getReference() + " from the business account, then confirm the manual refund on "
                            + "the booking.");
        }
        return p;
    }

    // ---------------------------------------------------------------- helpers

    private Booking lockBooking(UUID bookingId) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        return bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    /** The booking's payment, locked; it must be a cash payment. */
    private Payment cashPayment(UUID bookingId) {
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null || p.getMethod() != PaymentMethod.CASH) {
            throw ApiException.conflict("This booking is not being paid in cash");
        }
        return p;
    }

    /** The booking's payment, locked; it must be paid by QR payment or bank transfer (or in cash, earlier). */
    private Payment manualPayment(UUID bookingId) {
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null || !p.getMethod().isManual()) {
            throw ApiException.conflict("Cash at the counter can be recorded only for the final balance of a rental "
                    + "paid by QR payment or bank transfer");
        }
        return p;
    }

    /** Not while a transfer receipt waits for verification: the customer may already have transferred the money. */
    private void requireNoPendingReceipt(UUID bookingId) {
        if (receipts.findPendingByBookingId(bookingId).isPresent()) {
            throw ApiException.conflict("A payment receipt for this booking is waiting for verification; approve or "
                    + "reject it first");
        }
    }

    private void requireAmount(BigDecimal confirmed, BigDecimal due) {
        if (confirmed == null || confirmed.compareTo(due) != 0) {
            throw ApiException.conflict("The amount due is " + money(due)
                    + "; refresh the booking and check the amount before confirming");
        }
    }

    private boolean recorded(Payment p, PaymentTransaction.Kind kind) {
        return transactions.countByPayment_PaymentIdAndKind(p.getPaymentId(), kind) > 0;
    }

    /** The final balance was recorded as cash (not approved as a QR payment or bank transfer). */
    private boolean cashBalanceRecorded(Payment p) {
        return transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(p.getPaymentId()).stream()
                .anyMatch(t -> t.getKind() == PaymentTransaction.Kind.BALANCE && t.getMethod() == PaymentMethod.CASH
                        && t.getStatus() == PaymentTransaction.Status.SUCCESS);
    }

    /** Audit trail: the cash an admin recorded, with no provider reference. */
    private void record(Payment p, PaymentTransaction.Kind kind, BigDecimal amount, AuthUser admin) {
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(kind);
        t.setMethod(PaymentMethod.CASH);
        t.setAmount(amount);
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setRecordedBy(users.getReferenceById(admin.id()));
        t.setCreatedAt(now());
        transactions.save(t);
    }

    private String money(BigDecimal amount) {
        return currency + " " + PricingService.money(amount).toPlainString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
