package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.AdminReceiptResponse;
import com.scooterrentalkandy.spring.dto.ManualPaymentDtos.ReceiptSummary;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
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
import com.scooterrentalkandy.spring.services.PaymentSettingsService.ImageContent;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.CheckedPhoto;
import com.scooterrentalkandy.spring.services.RepairPhotoValidator.Messages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

/**
 * QR payments and bank transfers (BE-045). The customer transfers the money outside the application and uploads the
 * receipt; nothing here moves money or claims to. A receipt only waits for verification: the money counts as
 * received when an admin approves the receipt after checking the business account, and never before.
 *
 * <p>Initial payment: the customer chooses QR or bank transfer (the payment stays PENDING, the booking awaiting
 * payment), transfers the initial charge and uploads the receipt. While it waits for verification the booking stays
 * reserved beyond the usual payment hold, up to the verification hold (see {@link UnpaidBookingExpiry}). Approval
 * publishes {@link InitialPaymentSucceeded}, so the booking becomes READY_FOR_PICKUP through the same lifecycle as card and
 * cash. A rejected receipt stays on record with its reason; the customer may upload a replacement (a new receipt).
 *
 * <p>Final balance: when a rental paid at the counter or by transfer ends with a balance due, the customer may
 * transfer exactly that balance and upload a receipt (by QR payment or bank transfer, whichever is enabled, also when
 * the first payment was cash);
 * approval records it and publishes {@link BalancePaid}, completing the rental. A refund due on a QR or bank transfer
 * payment is made by an admin outside the application and recorded here; no payment provider is ever called.
 *
 * <p>Every approval and refund names the amount the admin saw; any other amount is refused. Repeating an approval
 * that is already recorded changes nothing. Lock order as in {@link PaymentService}: the booking row, then the
 * payment row.
 */
@Service
public class ManualPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ManualPaymentService.class);
    static final Messages RECEIPT = Messages.forOne("receipt");
    private static final int MAX_REFERENCE = 100;

    private final BookingRepository bookings;
    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentReceiptRepository receipts;
    private final UserRepository users;
    private final PaymentService cardPayments;
    private final PaymentSettingsService settings;
    private final PricingService pricing;
    private final NotificationService notifications;
    private final PaymentImageStorage images;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public ManualPaymentService(BookingRepository bookings, PaymentRepository payments,
                                PaymentTransactionRepository transactions, PaymentReceiptRepository receipts,
                                UserRepository users, PaymentService cardPayments, PaymentSettingsService settings,
                                PricingService pricing, NotificationService notifications, PaymentImageStorage images,
                                ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.payments = payments;
        this.transactions = transactions;
        this.receipts = receipts;
        this.users = users;
        this.cardPayments = cardPayments;
        this.settings = settings;
        this.pricing = pricing;
        this.notifications = notifications;
        this.images = images;
        this.events = events;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    /** "QR payment", "bank transfer" or "cash payment", for messages. */
    public static String label(PaymentMethod method) {
        return switch (method) {
            case QR_TRANSFER -> "QR payment";
            case BANK_TRANSFER -> "bank transfer";
            case CASH -> "cash payment";
            default -> "card payment";
        };
    }

    // ---------------------------------------------------------------- customer

    /**
     * The customer will pay the initial charge by QR payment or bank transfer. The payment stays PENDING and the
     * booking awaiting payment until an admin approves a receipt. An open card attempt is closed first. Not while a
     * receipt waits for verification: the customer may already have transferred the money.
     */
    @Transactional
    public void choose(UUID bookingId, PaymentMethod method, AuthUser user) {
        if (method == null || !method.isTransfer()) {
            throw ApiException.badRequest("Choose QR payment or bank transfer");
        }
        Booking b = lockBooking(bookingId);
        requireOwner(b, user);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("This booking is not awaiting payment (status " + b.getStatus() + ")");
        }
        if (!b.isContractSigned()) {
            throw ApiException.badRequest("Please sign the rental agreement before paying");
        }
        if (!settings.offers(method)) {
            throw ApiException.conflict(capitalised(label(method)) + " is not available right now; choose another "
                    + "payment method");
        }
        BigDecimal amount = pricing.initialCharge(b.getScooter(), PricingService.selectionsOf(b)).total();
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null) {
            p = new Payment();
            p.setBooking(b);
        } else if (p.getStatus() == PaymentStatus.SUCCESS || p.getStatus() == PaymentStatus.REFUNDED) {
            throw ApiException.conflict("This booking has already been paid");
        } else {
            requireNoPendingReceipt(bookingId);
            cardPayments.closeCardAttempt(p, "Replaced by a " + label(method));
        }
        p.setMethod(method);
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
    }

    /**
     * The customer uploads the receipt of a transfer they made: for the initial charge (by the method they chose) or
     * for the final balance due (by QR payment or bank transfer, whichever is offered). The receipt waits for an
     * admin; nothing is marked as paid.
     */
    @Transactional
    public void submitReceipt(UUID bookingId, PaymentMethod method, String reference, MultipartFile file,
                              AuthUser user) {
        if (file == null) {
            throw ApiException.badRequest("Attach a photo or screenshot of the payment receipt");
        }
        CheckedPhoto image = RepairPhotoValidator.check(file, RECEIPT);
        String transferReference = reference(reference);
        Booking b = lockBooking(bookingId);
        requireOwner(b, user);
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElseThrow(() ->
                ApiException.conflict("Choose QR payment or bank transfer at checkout before uploading a receipt"));
        requireNoPendingReceipt(bookingId);
        PaymentReceipt.Kind kind;
        BigDecimal amount;
        PaymentMethod paidBy;
        if (b.getStatus() == BookingStatus.PENDING) {
            if (p.getStatus() != PaymentStatus.PENDING || !p.getMethod().isTransfer()) {
                throw ApiException.conflict("Choose QR payment or bank transfer at checkout before uploading a "
                        + "receipt");
            }
            if (method != null && method != p.getMethod()) {
                throw ApiException.badRequest("This booking is being paid by " + label(p.getMethod())
                        + "; upload that receipt");
            }
            if (!now().isBefore(holdStart(b).plusMinutes(BookingScheduler.PAYMENT_HOLD_MINUTES))) {
                throw ApiException.conflict("The " + BookingScheduler.PAYMENT_HOLD_MINUTES + "-minute payment hold "
                        + "on this booking has ended, so it can no longer be paid");
            }
            kind = PaymentReceipt.Kind.INITIAL;
            amount = p.getAmount();
            paidBy = p.getMethod();
        } else if (b.getStatus() == BookingStatus.ACTIVE && b.getEndedAt() != null && p.getBalanceDue() != null
                && p.getMethod().isManual()) {
            if (method == null || !method.isTransfer()) {
                throw ApiException.badRequest("Say whether the balance was paid by QR payment or bank transfer");
            }
            if (!settings.offers(method)) {
                throw ApiException.conflict(capitalised(label(method)) + " is not available right now");
            }
            kind = PaymentReceipt.Kind.BALANCE;
            amount = p.getBalanceDue();
            paidBy = method;
        } else {
            throw ApiException.conflict("No payment by QR or bank transfer is due on this booking");
        }

        String key = RepairPhotoValidator.newKey(image.type());
        images.save(key, image.content());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    try {
                        images.delete(key);
                    } catch (RuntimeException e) {
                        log.warn("Could not remove an unsaved receipt ({})", e.getClass().getSimpleName());
                    }
                }
            }
        });
        PaymentReceipt r = new PaymentReceipt();
        r.setPayment(p);
        r.setKind(kind);
        r.setMethod(paidBy);
        r.setAmount(amount);
        r.setTransferReference(transferReference);
        r.setStorageKey(key);
        r.setContentType(image.type().mediaType);
        r.setSizeBytes(image.content().length);
        r.setStatus(PaymentReceipt.Status.PENDING_VERIFICATION);
        r.setSubmittedAt(now());
        receipts.save(r);
        notifications.notifyAdmins("Payment receipt to verify " + b.getReference(), b.getUser().getFullName()
                + " uploaded a " + label(paidBy) + " receipt for " + money(amount) + " ("
                + (kind == PaymentReceipt.Kind.INITIAL ? "initial payment" : "final balance") + ") for "
                + b.getReference() + ". Check that the money reached the business account, then approve or reject "
                + "it under Payments.");
    }

    /** One receipt image: the booking's customer or an admin. Anyone else is told it does not exist. */
    @Transactional(readOnly = true)
    public ImageContent receiptImage(UUID receiptId, AuthUser user) {
        PaymentReceipt r = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        if (!user.isAdmin() && !r.getPayment().getBooking().getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Receipt");
        }
        return new ImageContent(r.getContentType(), images.read(r.getStorageKey()));
    }

    /** The receipts of a payment, oldest first, as its customer sees them. */
    @Transactional(readOnly = true)
    public List<ReceiptSummary> forPayment(Payment p) {
        if (p == null || p.getPaymentId() == null) {
            return List.of();
        }
        return receipts.findByPayment_PaymentIdOrderBySubmittedAtAsc(p.getPaymentId()).stream()
                .map(r -> new ReceiptSummary(r.getPaymentReceiptId(), r.getKind(), r.getMethod(), r.getAmount(),
                        r.getTransferReference(), r.getStatus(), r.getSubmittedAt(), r.getReviewedAt(),
                        r.getRejectionReason()))
                .toList();
    }

    // ---------------------------------------------------------------- admin

    /** Recent receipts, newest first; waiting ones first when {@code status} is not given. */
    @Transactional(readOnly = true)
    public List<AdminReceiptResponse> list(PaymentReceipt.Status status) {
        return receipts.findTop300ByOrderBySubmittedAtDesc().stream()
                .filter(r -> status == null || r.getStatus() == status)
                .sorted(Comparator.comparing((PaymentReceipt r) -> r.getStatus() != PaymentReceipt.Status.PENDING_VERIFICATION))
                .map(this::toAdmin)
                .toList();
    }

    /**
     * An admin checked the business account and the money arrived: the receipt is approved and the amount recorded
     * as received. Initial payment: the booking becomes READY_FOR_PICKUP through the normal lifecycle. Final balance: the
     * rental completes. Only a receipt waiting for verification can be approved; approving it again changes nothing.
     */
    @Transactional
    public AdminReceiptResponse approve(UUID receiptId, BigDecimal amount, AuthUser admin) {
        UUID bookingId = receipts.findBookingIdByReceiptId(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        Booking b = lockBooking(bookingId);
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Receipt"));
        PaymentReceipt r = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        switch (r.getStatus()) {
            case APPROVED -> {
                return toAdmin(r); // already approved
            }
            case REJECTED -> throw ApiException.conflict("This receipt was rejected, so it cannot be approved; "
                    + "review the customer's new receipt instead");
            case EXPIRED -> throw ApiException.conflict("This booking was released before the receipt was verified, "
                    + "so it cannot be approved. If the money arrived, refund it to the customer from the business "
                    + "account");
            default -> {
            }
        }
        requireAmount(amount, r.getAmount());
        LocalDateTime now = now();
        if (r.getKind() == PaymentReceipt.Kind.INITIAL) {
            if (b.getStatus() != BookingStatus.PENDING || p.getStatus() != PaymentStatus.PENDING
                    || p.getMethod() != r.getMethod() || p.getAmount().compareTo(r.getAmount()) != 0) {
                throw ApiException.conflict("This booking no longer expects this payment (booking " + b.getStatus()
                        + "); refresh and check it");
            }
            p.setStatus(PaymentStatus.SUCCESS);
            p.setPaidAt(now);
            p.setFailureReason(null);
            approved(r, admin, now);
            record(p, PaymentTransaction.Kind.INITIAL, r, admin, now);
            log.info("{} {} for booking {} approved by admin {}", r.getMethod(), r.getAmount(), b.getReference(),
                    admin.id());
            events.publishEvent(new InitialPaymentSucceeded(bookingId));
        } else {
            if (b.getStatus() != BookingStatus.ACTIVE || b.getEndedAt() == null || p.getBalanceDue() == null
                    || p.getBalanceDue().compareTo(r.getAmount()) != 0) {
                throw ApiException.conflict("This booking no longer has a balance of " + money(r.getAmount())
                        + " due; refresh and check it");
            }
            BigDecimal due = p.getBalanceDue();
            approved(r, admin, now);
            record(p, PaymentTransaction.Kind.BALANCE, r, admin, now);
            p.setAmount(p.getAmount().add(due));
            p.setBalanceDue(null);
            p.setPaidAt(now);
            p.setFailureReason(null);
            log.info("{} balance {} for booking {} approved by admin {}", r.getMethod(), due, b.getReference(),
                    admin.id());
            notifications.notify(b.getUser(), "Final payment approved: " + b.getReference(),
                    "Your " + label(r.getMethod()) + " of " + money(due) + " was approved. Thank you.");
            notifications.notifyAdmins("Final payment approved " + b.getReference(), "The final "
                    + label(r.getMethod()) + " of " + money(due) + " for " + b.getReference() + " was approved.");
            events.publishEvent(new BalancePaid(bookingId));
        }
        return toAdmin(r);
    }

    /**
     * An admin rejects a receipt (wrong amount, unreadable, transfer not found, wrong account or reference). Nothing
     * is paid and the booking does not change; the receipt stays on record with the reason, and the customer may
     * upload a new one. For the initial payment the payment hold starts again from the rejection.
     */
    @Transactional
    public AdminReceiptResponse reject(UUID receiptId, String reason, AuthUser admin) {
        UUID bookingId = receipts.findBookingIdByReceiptId(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        Booking b = lockBooking(bookingId);
        payments.findByBookingIdForUpdate(bookingId);
        PaymentReceipt r = receipts.findById(receiptId).orElseThrow(() -> ApiException.notFound("Receipt"));
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw ApiException.badRequest("Give the reason for rejecting the receipt");
        }
        switch (r.getStatus()) {
            case REJECTED -> {
                return toAdmin(r); // already rejected
            }
            case APPROVED -> throw ApiException.conflict("This receipt was already approved");
            case EXPIRED -> throw ApiException.conflict("This booking was released before the receipt was verified");
            default -> {
            }
        }
        LocalDateTime now = now();
        r.setStatus(PaymentReceipt.Status.REJECTED);
        r.setRejectionReason(why);
        r.setReviewedBy(users.getReferenceById(admin.id()));
        r.setReviewedAt(now);
        log.info("{} receipt for booking {} rejected by admin {}", r.getMethod(), b.getReference(), admin.id());
        String next = r.getKind() == PaymentReceipt.Kind.INITIAL
                ? " You can upload a new receipt within " + BookingScheduler.PAYMENT_HOLD_MINUTES
                        + " minutes; otherwise the booking is released."
                : " Please upload a new receipt for the balance.";
        notifications.notify(b.getUser(), "Payment receipt not accepted: " + b.getReference(),
                "Your " + label(r.getMethod()) + " receipt for " + money(r.getAmount()) + " was not accepted: " + why
                        + "." + next);
        return toAdmin(r);
    }

    /**
     * An admin made the refund due on a QR or bank transfer payment outside the application (for example a bank
     * transfer back to the customer) and records it. The refund counts as completed only now. At the end of a
     * rental the rental then completes. No payment provider is called.
     */
    @Transactional
    public void confirmRefund(UUID bookingId, BigDecimal amount, String reference, String note, AuthUser admin) {
        Booking b = lockBooking(bookingId);
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null || !p.getMethod().isTransfer()) {
            throw ApiException.conflict(p != null && p.getMethod() == PaymentMethod.CASH
                    ? "This booking was paid in cash: confirm the cash refund instead"
                    : "This booking was not paid by QR payment or bank transfer");
        }
        if (p.getRefundDue() == null) {
            if (transactions.countByPayment_PaymentIdAndKind(p.getPaymentId(), PaymentTransaction.Kind.REFUND) > 0) {
                return; // already recorded
            }
            throw ApiException.conflict("No refund is due on this booking");
        }
        BigDecimal refund = p.getRefundDue();
        requireAmount(amount, refund);
        LocalDateTime now = now();
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(PaymentTransaction.Kind.REFUND);
        t.setMethod(p.getMethod());
        t.setAmount(refund);
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setReference(text(reference, MAX_REFERENCE));
        t.setNote(text(note, 500));
        t.setRecordedBy(users.getReferenceById(admin.id()));
        t.setCreatedAt(now);
        transactions.save(t);
        if (refund.compareTo(p.getAmount()) >= 0) {
            p.setStatus(PaymentStatus.REFUNDED); // everything received was paid back; the amount stays as paid
        } else {
            p.setAmount(p.getAmount().subtract(refund));
        }
        p.setRefundDue(null);
        log.info("Manual refund {} for booking {} recorded by admin {}", refund, b.getReference(), admin.id());
        notifications.notify(b.getUser(), "Refund completed: " + b.getReference(),
                "A refund of " + money(refund) + " for " + b.getReference() + " was sent to you by "
                        + label(p.getMethod()) + ".");
        events.publishEvent(new CashRefunded(bookingId));
    }

    // ---------------------------------------------------------------- booking lifecycle

    /**
     * The booking is being released (the hold or the verification hold ran out, or the customer cancelled) while a
     * receipt waits for verification: the receipt expires unapproved and the admins are told to check whether the
     * money arrived, since it would then have to be refunded from the business account.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void expirePendingReceipt(Booking b, boolean verificationTimedOut) {
        receipts.findPendingByBookingId(b.getBookingId()).ifPresent(r -> {
            r.setStatus(PaymentReceipt.Status.EXPIRED);
            r.setReviewedAt(now());
            String what = label(r.getMethod()) + " of " + money(r.getAmount());
            if (verificationTimedOut) {
                notifications.notify(b.getUser(), "Booking released: " + b.getReference(), "Your " + what
                        + " could not be verified in time, so booking " + b.getReference() + " was released. If "
                        + "the money left your account, our team will refund it.");
            }
            notifications.notifyAdmins("Unverified receipt " + b.getReference(), "Booking " + b.getReference()
                    + (verificationTimedOut ? " was released because its " + what + " was not verified in time."
                            : " was cancelled while its " + what + " was waiting for verification.")
                    + " Check the business account; if the money arrived, refund it to the customer.");
        });
    }

    // ---------------------------------------------------------------- helpers

    private Booking lockBooking(UUID bookingId) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        return bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private static void requireOwner(Booking b, AuthUser user) {
        if (!b.getUser().getUserId().equals(user.id())) {
            if (user.isAdmin()) {
                throw ApiException.forbidden();
            }
            throw ApiException.notFound("Booking");
        }
    }

    private void requireNoPendingReceipt(UUID bookingId) {
        if (receipts.findPendingByBookingId(bookingId).isPresent()) {
            throw ApiException.conflict("A payment receipt for this booking is waiting for verification");
        }
    }

    /** The initial payment hold runs from the booking, or again from the last rejected receipt. */
    private LocalDateTime holdStart(Booking b) {
        LocalDateTime rejected = receipts.lastInitialRejectionAt(b.getBookingId()).orElse(null);
        return rejected != null && rejected.isAfter(b.getCreatedAt()) ? rejected : b.getCreatedAt();
    }

    private void requireAmount(BigDecimal confirmed, BigDecimal due) {
        if (confirmed == null || confirmed.compareTo(due) != 0) {
            throw ApiException.conflict("The amount due is " + money(due)
                    + "; refresh and check the amount before confirming");
        }
    }

    private void approved(PaymentReceipt r, AuthUser admin, LocalDateTime now) {
        r.setStatus(PaymentReceipt.Status.APPROVED);
        r.setReviewedBy(users.getReferenceById(admin.id()));
        r.setReviewedAt(now);
    }

    /** Audit trail: the approved transfer, with the customer's reference and the approving admin. */
    private void record(Payment p, PaymentTransaction.Kind kind, PaymentReceipt r, AuthUser admin,
                        LocalDateTime now) {
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(kind);
        t.setMethod(r.getMethod());
        t.setAmount(r.getAmount());
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setReference(r.getTransferReference());
        t.setRecordedBy(users.getReferenceById(admin.id()));
        t.setCreatedAt(now);
        transactions.save(t);
    }

    private AdminReceiptResponse toAdmin(PaymentReceipt r) {
        Payment p = r.getPayment();
        Booking b = p.getBooking();
        BigDecimal due = null;
        if (r.getKind() == PaymentReceipt.Kind.INITIAL && b.getStatus() == BookingStatus.PENDING
                && p.getStatus() == PaymentStatus.PENDING) {
            due = p.getAmount();
        } else if (r.getKind() == PaymentReceipt.Kind.BALANCE && b.getStatus() == BookingStatus.ACTIVE) {
            due = p.getBalanceDue();
        }
        return new AdminReceiptResponse(r.getPaymentReceiptId(), b.getBookingId(), b.getReference(), b.getStatus(),
                b.getUser().getFullName(), b.getUser().getEmail(), r.getKind(), r.getMethod(), r.getAmount(), due,
                r.getTransferReference(), r.getStatus(), r.getSubmittedAt(),
                r.getReviewedBy() == null ? null : r.getReviewedBy().getFullName(), r.getReviewedAt(),
                r.getRejectionReason());
    }

    /** The customer's transfer reference: optional, one line, at most 100 characters. */
    private static String reference(String reference) {
        String s = text(reference, Integer.MAX_VALUE);
        if (s == null) {
            return null;
        }
        if (s.length() > MAX_REFERENCE || s.chars().anyMatch(Character::isISOControl)) {
            throw ApiException.badRequest("The transaction reference must be one line of at most " + MAX_REFERENCE
                    + " characters");
        }
        return s;
    }

    private static String text(String s, int max) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String capitalised(String s) {
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private String money(BigDecimal amount) {
        return currency + " " + PricingService.money(amount).toPlainString();
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }
}
