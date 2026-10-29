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
import com.scooterrentalkandy.spring.services.PaymentEvents.InitialPaymentSucceeded;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash at the rental counter (BE-044). A cash payment is the booking's Payment row with method CASH and no provider,
 * payment intent or card details. The customer only chooses cash; the money counts as received only when an admin
 * records it, and cash handed back only when an admin records that too. Each amount an admin records is a
 * PaymentTransaction naming the admin, with no provider reference. Nothing here calls a payment provider.
 *
 * <p>The booking lifecycle is the one card payments use: recording the initial cash publishes
 * {@link InitialPaymentSucceeded} (the booking becomes ACTIVE), recording the final balance publishes
 * {@link BalancePaid} and recording a refund owed at the end publishes {@link CashRefunded} (the rental completes).
 *
 * <p>The settlement at the end of a rental ({@link #settleAtEnd}) and the refund owed after a cancellation serve
 * every manual method (cash, QR payment, bank transfer): the balance or refund due is kept on the payment. A balance
 * due on a booking first paid by QR payment or bank transfer may also be paid in cash here; each recorded amount
 * names its own method.
 *
 * <p>Every confirmation names the amount the admin saw; any other amount is refused, since the screen was out of
 * date. Repeating a confirmation that is already recorded changes nothing. Lock order as in {@link PaymentService}:
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
    private final PaymentService cardPayments;
    private final PricingService pricing;
    private final NotificationService notifications;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public CashPaymentService(BookingRepository bookings, PaymentRepository payments,
                              PaymentTransactionRepository transactions, PaymentReceiptRepository receipts,
                              UserRepository users,
                              PaymentService cardPayments, PricingService pricing, NotificationService notifications,
                              ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.payments = payments;
        this.transactions = transactions;
        this.receipts = receipts;
        this.users = users;
        this.cardPayments = cardPayments;
        this.pricing = pricing;
        this.notifications = notifications;
        this.events = events;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    // ---------------------------------------------------------------- customer

    /**
     * The customer chooses to pay the initial charge in cash at the counter. The payment stays PENDING and the
     * booking awaiting payment until an admin records the cash. An open card attempt is closed first. Choosing cash
     * again only refreshes the amount.
     */
    @Transactional
    public void choose(UUID bookingId, AuthUser user) {
        Booking b = lockBooking(bookingId);
        if (!b.getUser().getUserId().equals(user.id())) {
            if (user.isAdmin()) {
                throw ApiException.forbidden();
            }
            throw ApiException.notFound("Booking");
        }
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("This booking is not awaiting payment (status " + b.getStatus() + ")");
        }
        if (!b.isContractSigned()) {
            throw ApiException.badRequest("Please sign the rental agreement before paying");
        }
        BigDecimal amount = pricing.initialCharge(b.getScooter(), PricingService.selectionsOf(b)).total();
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        boolean alreadyChosen = p != null && p.getMethod() == PaymentMethod.CASH
                && p.getStatus() == PaymentStatus.PENDING;
        if (p == null) {
            p = new Payment();
            p.setBooking(b);
        } else if (p.getStatus() == PaymentStatus.SUCCESS || p.getStatus() == PaymentStatus.REFUNDED) {
            throw ApiException.conflict("This booking has already been paid");
        } else {
            requireNoPendingReceipt(bookingId);
            cardPayments.closeCardAttempt(p);
        }
        p.setMethod(PaymentMethod.CASH);
        p.setProvider(null);
        p.setStripePaymentId(null);
        p.setPaymentMethodRef(null);
        p.setCardLast4(null);
        p.setBalanceIntentId(null);
        p.setBalanceDue(null);
        p.setRefundDue(null);
        p.setAmount(amount);
        p.setStatus(PaymentStatus.PENDING);
        p.setFailureReason(null);
        payments.save(p);
        if (!alreadyChosen) {
            notifications.notifyAdmins("Cash payment pending " + b.getReference(), b.getUser().getFullName()
                    + " will pay " + money(amount) + " in cash at the rental counter for " + b.getReference()
                    + ". Confirm it on the booking when the cash is received.");
        }
    }

    // ---------------------------------------------------------------- admin confirmations

    /** An admin records the initial cash as received; the booking becomes ACTIVE through the normal lifecycle. */
    @Transactional
    public void confirmInitial(UUID bookingId, BigDecimal amount, AuthUser admin) {
        Booking b = lockBooking(bookingId);
        Payment p = cashPayment(bookingId);
        if (p.getStatus() == PaymentStatus.SUCCESS || p.getStatus() == PaymentStatus.REFUNDED) {
            return; // already recorded
        }
        if (b.getStatus() != BookingStatus.PENDING || p.getStatus() != PaymentStatus.PENDING) {
            throw ApiException.conflict("This booking is " + b.getStatus().name().toLowerCase()
                    + ", so cash can no longer be accepted for it");
        }
        if (!b.isContractSigned()) {
            throw ApiException.badRequest("The customer must sign the rental agreement before paying");
        }
        requireAmount(amount, p.getAmount());
        p.setStatus(PaymentStatus.SUCCESS);
        p.setPaidAt(now());
        p.setFailureReason(null);
        record(p, PaymentTransaction.Kind.INITIAL, p.getAmount(), admin);
        log.info("Cash {} received for booking {} recorded by admin {}", p.getAmount(), b.getReference(), admin.id());
        events.publishEvent(new InitialPaymentSucceeded(bookingId));
    }

    /**
     * An admin records the remaining balance as received in cash at the counter; the rental completes. Also for a
     * booking first paid by QR payment or bank transfer, unless a transfer receipt for the balance is waiting.
     */
    @Transactional
    public void confirmBalance(UUID bookingId, BigDecimal amount, AuthUser admin) {
        Booking b = lockBooking(bookingId);
        Payment p = manualPayment(bookingId);
        if (p.getBalanceDue() == null && b.getStatus() == BookingStatus.COMPLETED
                && recorded(p, PaymentTransaction.Kind.BALANCE)) {
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

    /** The booking's payment, locked; it must be paid at the counter or by transfer. */
    private Payment manualPayment(UUID bookingId) {
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null || !p.getMethod().isManual()) {
            throw ApiException.conflict("This booking is not being paid in cash");
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
