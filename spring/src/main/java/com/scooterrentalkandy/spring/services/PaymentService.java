package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentConfigResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentIntentResponse;
import com.scooterrentalkandy.spring.dto.PaymentDtos.PaymentResponse;
import com.scooterrentalkandy.spring.entity.PaymentProviderEvent;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.PaymentMapper;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentProviderEventRepository;
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PaymentEvents.BalancePaid;
import com.scooterrentalkandy.spring.services.PaymentEvents.InitialPaymentSucceeded;
import com.scooterrentalkandy.spring.services.PaymentGateway.ChargeResult;
import com.scooterrentalkandy.spring.services.PaymentGateway.Event;
import com.scooterrentalkandy.spring.services.PaymentGateway.Outcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * One Payment per booking, following the Stripe PaymentIntent flow (SDS 4.2, 4.3): the initial charge (1 hour +
 * 1 gear day) is an intent the customer confirms in the browser; the provider's webhook activates the booking.
 * When the rental ends, the remaining balance is charged to the saved payment method and added to the same row.
 * If that fails, the balance stays due on the row and can be paid online.
 *
 * <p>The provider is optional: without one, payment operations answer 503 and nothing is simulated. Cash at the
 * rental counter ({@link CashPaymentService}) and QR payments and bank transfers ({@link ManualPaymentService}) are
 * separate: they never reach the provider, and a payment by one of those methods is never given to the card
 * operations here.
 *
 * <p>Safety rules:
 * <ul>
 *   <li>Lock order: the booking row, then the payment row, for every change (webhooks included).</li>
 *   <li>Provider calls with side effects carry idempotency keys derived from the booking, the attempt number or
 *       the intent, so a retry after a rolled-back transaction gets the provider's original answer.</li>
 *   <li>Money the provider took for an intent this booking no longer expects (replaced, closed, wrong amount) is
 *       refunded, never ignored. Each intent is refunded at most once.</li>
 *   <li>A refund counts as done only when the provider says it succeeded (at once or by a charge.refunded
 *       event); until then its transaction stays PENDING.</li>
 * </ul>
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String INITIAL = "INITIAL";
    static final String BALANCE = "BALANCE";
    private static final Set<String> HANDLED = Set.of(Event.SUCCEEDED, Event.FAILED, Event.CANCELED,
            Event.CHARGE_REFUNDED);

    private final PaymentRepository payments;
    private final PaymentReceiptRepository receipts;
    private final PaymentTransactionRepository transactions;
    private final PaymentProviderEventRepository providerEvents;
    private final BookingRepository bookings;
    private final ObjectProvider<PaymentGateway> gateways;
    private final PricingService pricing;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public PaymentService(PaymentRepository payments, PaymentTransactionRepository transactions,
                          PaymentReceiptRepository receipts,
                          PaymentProviderEventRepository providerEvents, BookingRepository bookings,
                          ObjectProvider<PaymentGateway> gateways, PricingService pricing,
                          ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.payments = payments;
        this.transactions = transactions;
        this.receipts = receipts;
        this.providerEvents = providerEvents;
        this.bookings = bookings;
        this.gateways = gateways;
        this.pricing = pricing;
        this.events = events;
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    public PaymentConfigResponse config() {
        PaymentGateway gateway = gateways.getIfAvailable();
        return gateway == null ? new PaymentConfigResponse(false, null, null, currency)
                : new PaymentConfigResponse(true, gateway.name(), gateway.publishableKey(), currency);
    }

    /** The configured provider; 503 if there is none. Never a stand-in. */
    private PaymentGateway gateway() {
        PaymentGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Card payments are not available: no payment provider is configured");
        }
        return gateway;
    }

    // ---------------------------------------------------------------- payment intents

    /** SDS 4.2 steps 7-10: create the payment intent for the initial charge and hand its client secret back. */
    @Transactional
    public PaymentIntentResponse createInitialIntent(UUID bookingId, AuthUser user) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        requireOwner(b, user);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("This booking is not awaiting payment (status " + b.getStatus() + ")");
        }
        if (!b.isContractSigned()) {
            throw ApiException.badRequest("Please sign the rental agreement before paying");
        }
        PaymentGateway gateway = gateway();
        BigDecimal amount = pricing.initialCharge(b.getScooter(), PricingService.selectionsOf(b)).total();

        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (p == null) {
            p = new Payment();
            p.setBooking(b);
            p.setMethod(PaymentMethod.CARD);
            p.setProvider(gateway.name());
        } else if (p.getStatus() == PaymentStatus.SUCCESS || p.getStatus() == PaymentStatus.REFUNDED) {
            throw ApiException.conflict("This booking has already been paid");
        } else if (receipts.findPendingByBookingId(bookingId).isPresent()) {
            // The customer may already have transferred the money: an admin verifies that first.
            throw ApiException.conflict("A payment receipt for this booking is waiting for verification");
        } else if (p.getStripePaymentId() != null) {
            replace(gateway, p, p.getStripePaymentId());
        }
        if (p.getMethod().isManual()) {
            // The customer chose cash, QR payment or bank transfer earlier but pays by card after all; nothing was
            // recorded as received (status PENDING).
            p.setMethod(PaymentMethod.CARD);
            p.setProvider(gateway.name());
        }
        int attempt = attempts(p, PaymentTransaction.Kind.INITIAL) + 1;
        PaymentGateway.Intent intent = gateway.createIntent(amount, currency, customer(gateway, b.getUser()),
                "Scooter Rent Kandy booking " + b.getReference(), metadata(b, INITIAL),
                "booking-" + b.getBookingId() + "-initial-intent-" + attempt);
        p.setAmount(amount);
        p.setStatus(PaymentStatus.PENDING);
        p.setStripePaymentId(intent.id());
        p.setFailureReason(null);
        payments.save(p);
        record(p, PaymentTransaction.Kind.INITIAL, intent.id(), amount, PaymentTransaction.Status.PENDING, null);
        return new PaymentIntentResponse(p.getPaymentId(), b.getBookingId(), INITIAL, intent.id(), intent.clientSecret(),
                amount, currency, gateway.name());
    }

    /** Pay a remaining balance online, after the off-session charge at completion failed. */
    @Transactional
    public PaymentIntentResponse createBalanceIntent(UUID bookingId, AuthUser user) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        requireOwner(b, user);
        Payment p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (b.getStatus() != BookingStatus.ACTIVE || p == null || p.getBalanceDue() == null) {
            throw ApiException.conflict("This booking has no balance due");
        }
        if (p.getMethod() == PaymentMethod.CASH) {
            throw ApiException.conflict("This booking is paid in cash: pay the balance at the rental counter");
        }
        if (p.getMethod().isTransfer()) {
            throw ApiException.conflict("This booking is paid by transfer: transfer the balance and upload the "
                    + "receipt, or pay it at the rental counter");
        }
        PaymentGateway gateway = gateway();
        boolean chargeProcessing = transactions.findByPayment_PaymentIdAndStatus(p.getPaymentId(),
                        PaymentTransaction.Status.PENDING).stream()
                .anyMatch(t -> t.getKind() == PaymentTransaction.Kind.BALANCE
                        && !Objects.equals(t.getProviderRef(), p.getBalanceIntentId()));
        if (chargeProcessing) {
            throw ApiException.conflict("The charge to your saved card is still being processed; "
                    + "please wait a moment and refresh");
        }
        if (p.getBalanceIntentId() != null) {
            replace(gateway, p, p.getBalanceIntentId());
        }
        int attempt = attempts(p, PaymentTransaction.Kind.BALANCE) + 1;
        PaymentGateway.Intent intent = gateway.createIntent(p.getBalanceDue(), currency,
                customer(gateway, b.getUser()), "Scooter Rent Kandy balance " + b.getReference(),
                metadata(b, BALANCE), "booking-" + b.getBookingId() + "-balance-intent-" + attempt);
        p.setBalanceIntentId(intent.id());
        record(p, PaymentTransaction.Kind.BALANCE, intent.id(), p.getBalanceDue(), PaymentTransaction.Status.PENDING,
                null);
        return new PaymentIntentResponse(p.getPaymentId(), b.getBookingId(), BALANCE, intent.id(), intent.clientSecret(),
                p.getBalanceDue(), currency, gateway.name());
    }

    /**
     * Before an unpaid booking switches to QR payment or bank transfer: closes its card payment intent, if it has
     * one, so it can no longer be paid; {@code reason} says which method replaced it. Without a configured provider
     * the intent cannot be paid either and is closed here only. If the provider can no longer cancel it, the customer
     * may be paying right now: 409 and nothing changes.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void closeCardAttempt(Payment p, String reason) {
        if (p.getMethod().isManual() || p.getStripePaymentId() == null) {
            return;
        }
        PaymentGateway gateway = gateways.getIfAvailable();
        if (gateway == null) {
            log.warn("No payment provider configured; intent {} closed locally only", p.getStripePaymentId());
            settle(p, p.getStripePaymentId(), PaymentTransaction.Status.FAILED, reason);
        } else {
            replace(gateway, p, p.getStripePaymentId());
        }
    }

    /**
     * Cancels an intent that a new attempt replaces. If the provider can no longer cancel it, the customer may
     * already have paid it, so no second intent is created.
     */
    private void replace(PaymentGateway gateway, Payment p, String intentId) {
        if (!gateway.cancelIntent(intentId)) {
            throw ApiException.conflict("Your previous payment attempt is still being processed; "
                    + "please wait a moment and refresh");
        }
        settle(p, intentId, PaymentTransaction.Status.FAILED, "Replaced by a new payment attempt");
    }

    /** The user's provider customer, created once (the payment method is saved on it for the balance charge). */
    private static String customer(PaymentGateway gateway, User user) {
        if (user.getStripeCustomerId() == null) {
            user.setStripeCustomerId(gateway.createCustomer(user.getUserId(), user.getEmail(), user.getFullName(),
                    "customer-" + user.getUserId()));
        }
        return user.getStripeCustomerId();
    }

    // ---------------------------------------------------------------- webhook

    /**
     * SDS 4.2 step 12: the provider's webhook. The booking row is locked before the payment row, the order every
     * booking and payment change uses, so a webhook waits for expiry, cancellation or completion instead of
     * deadlocking with them, and then sees their committed result. Each event is processed once: its id is stored
     * with the outcome, and a repeat delivery is skipped.
     */
    @Transactional
    public void handleWebhook(String payload, String signature) {
        PaymentGateway gateway = gateway();
        Event e;
        try {
            e = gateway.parseWebhook(payload, signature);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid webhook: " + ex.getMessage());
        }
        if (!HANDLED.contains(e.type())) {
            return;
        }
        UUID bookingId = bookingOf(e);
        Payment p = null;
        if (bookingId != null && bookings.findByIdForUpdate(bookingId).isPresent()) {
            p = payments.findByBookingIdForUpdate(bookingId).orElse(null);
        }
        if (providerEvents.existsByProviderAndEventId(gateway.name(), e.id())) {
            log.info("Webhook event {} already processed", e.id());
            return;
        }
        if (p == null) {
            log.warn("Webhook {} for intent {} matches no booking payment; ignored", e.type(), e.intentId());
        } else {
            switch (e.type()) {
                case Event.SUCCEEDED -> onSucceeded(gateway, p, e);
                case Event.FAILED -> onFailed(p, e);
                case Event.CANCELED -> onCanceled(p, e);
                default -> onRefunded(p, e);
            }
        }
        providerEvents.save(new PaymentProviderEvent(gateway.name(), e.id(), e.type(), LocalDateTime.now(clock)));
    }

    /** From the intent's metadata (set when we created it); else from the intent recorded on a payment. */
    private UUID bookingOf(Event e) {
        UUID id = parseId(e.metadata().get("bookingId"));
        if (id == null && e.intentId() != null) {
            id = transactions.findBookingIdsByProviderRef(e.intentId()).stream().findFirst().orElse(null);
        }
        return id;
    }

    /**
     * Accepted only for this booking's current initial intent, or its open balance (online intent or a saved-card
     * charge still processing), with the stored amount and currency. Any other success of an intent created for
     * this booking is money the booking does not expect, and is refunded.
     */
    private void onSucceeded(PaymentGateway gateway, Payment p, Event e) {
        PaymentTransaction t = intentTransaction(p, e.intentId());
        if (t == null) {
            log.warn("Intent {} was not created for booking {}; ignored", e.intentId(), p.getBooking().getBookingId());
            return;
        }
        if (t.getStatus() == PaymentTransaction.Status.SUCCESS) {
            return; // already accounted for (a saved-card charge is recorded when it is made)
        }
        boolean currentInitial = t.getKind() == PaymentTransaction.Kind.INITIAL
                && e.intentId().equals(p.getStripePaymentId())
                && (p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.FAILED);
        boolean openBalance = t.getKind() == PaymentTransaction.Kind.BALANCE && p.getBalanceDue() != null
                && t.getStatus() == PaymentTransaction.Status.PENDING;
        if (currentInitial && matches(e, t.getAmount())) {
            p.setStatus(PaymentStatus.SUCCESS);
            p.setPaidAt(LocalDateTime.now(clock));
            p.setPaymentMethodRef(e.paymentMethodRef());
            p.setCardLast4(e.cardLast4());
            p.setFailureReason(null);
            t.setStatus(PaymentTransaction.Status.SUCCESS);
            t.setFailureReason(null);
            events.publishEvent(new InitialPaymentSucceeded(p.getBooking().getBookingId()));
        } else if (openBalance && matches(e, p.getBalanceDue())) {
            t.setStatus(PaymentTransaction.Status.SUCCESS);
            t.setFailureReason(null);
            if (p.getBalanceIntentId() != null && !p.getBalanceIntentId().equals(e.intentId())) {
                gateway.cancelIntent(p.getBalanceIntentId());
                settle(p, p.getBalanceIntentId(), PaymentTransaction.Status.FAILED, "Balance paid with the saved card");
            }
            p.setAmount(p.getAmount().add(p.getBalanceDue()));
            p.setBalanceDue(null);
            p.setBalanceIntentId(null);
            p.setPaidAt(LocalDateTime.now(clock));
            if (e.paymentMethodRef() != null) {
                p.setPaymentMethodRef(e.paymentMethodRef());
                p.setCardLast4(e.cardLast4());
            }
            p.setFailureReason(null);
            events.publishEvent(new BalancePaid(p.getBooking().getBookingId()));
        } else {
            refundUnexpected(gateway, p, t, e, currentInitial || openBalance
                    ? "The provider reported a different amount or currency"
                    : "Paid after it was replaced or closed");
        }
    }

    private void refundUnexpected(PaymentGateway gateway, Payment p, PaymentTransaction t, Event e, String why) {
        log.warn("Intent {} of booking {} succeeded with {} {} but was not expected ({}); refunding", e.intentId(),
                p.getBooking().getBookingId(), e.currency(), e.amount(), why);
        // The provider did take the money; the refund below gives it back.
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setFailureReason(why + "; refunded");
        if (e.intentId().equals(p.getStripePaymentId()) && p.getStatus() != PaymentStatus.SUCCESS
                && p.getStatus() != PaymentStatus.REFUNDED) {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(why);
        }
        refundOnce(gateway, p, e.intentId(), e.amount(), why + " (booking " + p.getBooking().getReference() + ")",
                "full");
    }

    private void onFailed(Payment p, Event e) {
        PaymentTransaction t = intentTransaction(p, e.intentId());
        if (t == null) {
            return;
        }
        if (t.getKind() == PaymentTransaction.Kind.INITIAL && e.intentId().equals(p.getStripePaymentId())
                && (p.getStatus() == PaymentStatus.PENDING || p.getStatus() == PaymentStatus.FAILED)) {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        } else if (t.getKind() == PaymentTransaction.Kind.BALANCE && e.intentId().equals(p.getBalanceIntentId())) {
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        }
    }

    /** A cancelled intent can never be paid: an open payment on it fails, and the booking stays payable. */
    private void onCanceled(Payment p, Event e) {
        PaymentTransaction t = intentTransaction(p, e.intentId());
        if (t == null || t.getStatus() != PaymentTransaction.Status.PENDING) {
            return;
        }
        String reason = "The payment was cancelled";
        t.setStatus(PaymentTransaction.Status.FAILED);
        t.setFailureReason(reason);
        if (t.getKind() == PaymentTransaction.Kind.INITIAL && e.intentId().equals(p.getStripePaymentId())
                && p.getStatus() == PaymentStatus.PENDING) {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(reason);
        } else if (t.getKind() == PaymentTransaction.Kind.BALANCE && e.intentId().equals(p.getBalanceIntentId())) {
            p.setBalanceIntentId(null);
        }
    }

    /**
     * The provider's running total refunded on an intent's charge. Our refunds on that intent are confirmed once
     * the total covers them; anything beyond them was refunded at the provider directly and is recorded. The
     * payment becomes REFUNDED only when its whole initial charge (and nothing else) has been refunded.
     */
    private void onRefunded(Payment p, Event e) {
        PaymentTransaction t = intentTransaction(p, e.intentId());
        if (t == null || e.amountRefunded() == null) {
            return;
        }
        List<PaymentTransaction> ours = transactions.findByPayment_PaymentIdAndKindAndIntentRef(p.getPaymentId(),
                PaymentTransaction.Kind.REFUND, e.intentId()).stream()
                .filter(r -> r.getStatus() != PaymentTransaction.Status.FAILED).toList();
        BigDecimal known = ours.stream().map(PaymentTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (e.amountRefunded().compareTo(known) >= 0) {
            ours.stream().filter(r -> r.getStatus() == PaymentTransaction.Status.PENDING)
                    .forEach(r -> r.setStatus(PaymentTransaction.Status.SUCCESS));
        }
        if (e.amountRefunded().compareTo(known) > 0) {
            record(p, PaymentTransaction.Kind.REFUND, null, e.intentId(), e.amountRefunded().subtract(known),
                    PaymentTransaction.Status.SUCCESS, "Refunded at the payment provider");
        }
        if (t.getKind() == PaymentTransaction.Kind.INITIAL && e.intentId().equals(p.getStripePaymentId())
                && p.getStatus() == PaymentStatus.SUCCESS && e.amountRefunded().compareTo(t.getAmount()) >= 0
                && p.getAmount().compareTo(t.getAmount()) <= 0) {
            p.setStatus(PaymentStatus.REFUNDED);
        }
    }

    /** The provider's amount and currency are the ones stored when the intent was created. */
    private boolean matches(Event e, BigDecimal expected) {
        return e.amount() != null && e.amount().compareTo(expected) == 0 && currency.equalsIgnoreCase(e.currency());
    }

    // ---------------------------------------------------------------- end of rental and refunds

    /**
     * SDS 4.3 step 11 chargeRemainingBalance: charges {@code remaining} to the saved payment method and adds it
     * to the booking's payment. On failure the balance is recorded as due on the same row. Returns success. The
     * idempotency key counts recorded attempts: a declined attempt is recorded, so the next try is a new charge;
     * a rolled-back attempt is not, so its retry repeats the same key and cannot charge twice.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean chargeRemainingBalance(Booking b, BigDecimal remaining) {
        PaymentGateway gateway = gateway();
        Payment p = paid(b);
        int attempt = attempts(p, PaymentTransaction.Kind.BALANCE) + 1;
        ChargeResult r = gateway.chargeSaved(b.getUser().getStripeCustomerId(), p.getPaymentMethodRef(), remaining,
                currency, "Scooter Rent Kandy balance " + b.getReference(), metadata(b, BALANCE),
                "booking-" + b.getBookingId() + "-balance-charge-" + attempt);
        record(p, PaymentTransaction.Kind.BALANCE, r.reference(), remaining, status(r), r.failureReason());
        if (r.succeeded()) {
            p.setAmount(p.getAmount().add(remaining));
            p.setBalanceDue(null);
            if (p.getBalanceIntentId() != null) {
                gateway.cancelIntent(p.getBalanceIntentId());
                settle(p, p.getBalanceIntentId(), PaymentTransaction.Status.FAILED, "Balance paid with the saved card");
                p.setBalanceIntentId(null);
            }
            p.setPaidAt(LocalDateTime.now(clock));
            p.setFailureReason(null);
            return true;
        }
        p.setBalanceDue(remaining);
        p.setFailureReason(r.outcome() == Outcome.PENDING ? "The payment is still being processed"
                : r.failureReason());
        return false;
    }

    /**
     * Only when the final cost is below what was paid (e.g. rates were lowered during the rental, or an approved
     * repair reimbursement reduced the bill): the difference is refunded once and the payment amount becomes the
     * final cost. When nothing is owed at all the whole payment is refunded and recorded like a full refund (status
     * REFUNDED, the amount stays what was paid), since a payment amount is never zero. A refusal is recorded on the
     * payment for the team to resolve.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundExcess(Booking b, BigDecimal excess) {
        Payment p = paid(b);
        ChargeResult r = refundOnce(gateway(), p, p.getStripePaymentId(), excess,
                "Final cost below amount paid for " + b.getReference(), "excess");
        if (r != null && r.outcome() != Outcome.FAILED) {
            if (excess.compareTo(p.getAmount()) >= 0) {
                if (r.succeeded()) {
                    p.setStatus(PaymentStatus.REFUNDED);
                }
            } else {
                p.setAmount(p.getAmount().subtract(excess));
            }
            p.setFailureReason(null);
        }
    }

    /**
     * Refunds the whole payment for a permitted cancellation. If the provider refuses, nothing changes (502) and
     * the customer can try again. Returns the payment, or null if nothing was paid.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment refundInFull(Booking b, String reason) {
        Payment p = payments.findByBookingIdForUpdate(b.getBookingId()).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.SUCCESS) {
            return null;
        }
        ChargeResult r = refundOnce(gateway(), p, p.getStripePaymentId(), p.getAmount(), reason, "full");
        if (r != null && r.outcome() == Outcome.FAILED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Refund failed: " + r.failureReason());
        }
        if (r != null && r.succeeded()) {
            p.setStatus(PaymentStatus.REFUNDED);
        }
        return p;
    }

    /**
     * A payment that succeeded after its booking expired or was cancelled is refunded in full. A refusal is
     * recorded instead of thrown, so the webhook is not retried forever; returns false so the team is told.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean refundLatePayment(Booking b, String reason) {
        Payment p = payments.findByBookingIdForUpdate(b.getBookingId()).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.SUCCESS) {
            return true;
        }
        ChargeResult r = refundOnce(gateway(), p, p.getStripePaymentId(), p.getAmount(), reason, "full");
        if (r != null && r.succeeded()) {
            p.setStatus(PaymentStatus.REFUNDED);
        }
        return r == null || r.outcome() != Outcome.FAILED;
    }

    /**
     * Refunds {@code amount} of {@code intentId} unless a refund for that intent is already pending or done, with
     * the idempotency key refund-{intentId}-{purpose}. Records the provider's answer; returns null if skipped.
     */
    private ChargeResult refundOnce(PaymentGateway gateway, Payment p, String intentId, BigDecimal amount,
                                    String reason, String purpose) {
        boolean alreadyRefunding = transactions.findByPayment_PaymentIdAndKindAndIntentRef(p.getPaymentId(),
                PaymentTransaction.Kind.REFUND, intentId).stream()
                .anyMatch(r -> r.getStatus() != PaymentTransaction.Status.FAILED);
        if (alreadyRefunding) {
            log.info("Intent {} already has a refund; not refunding again", intentId);
            return null;
        }
        ChargeResult r = gateway.refund(intentId, amount, currency, reason, "refund-" + intentId + "-" + purpose);
        record(p, PaymentTransaction.Kind.REFUND, r.reference(), intentId, amount, status(r), r.failureReason());
        if (r.outcome() == Outcome.FAILED) {
            p.setFailureReason("Refund of " + currency + " " + amount + " failed: " + r.failureReason());
        }
        return r;
    }

    /**
     * A booking cancelled before payment: close its open intent so it can no longer be paid. If the provider can
     * no longer cancel it (the customer may be paying right now), a later success is refunded by the booking
     * lifecycle. Without a configured provider the intent cannot be paid either; it is closed here only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenIntent(Booking b) {
        payments.findByBookingIdForUpdate(b.getBookingId()).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.PENDING && p.getMethod() == PaymentMethod.CASH) {
                // No cash was received and no provider is involved: the pending cash payment just lapses.
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason("Booking cancelled before the cash payment was received");
            } else if (p.getStatus() == PaymentStatus.PENDING && p.getMethod().isTransfer()) {
                // No transfer was approved and no provider is involved (a waiting receipt is expired by the caller).
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason("Booking cancelled before the transfer was verified");
            } else if (p.getStatus() == PaymentStatus.PENDING) {
                PaymentGateway gateway = gateways.getIfAvailable();
                if (gateway == null) {
                    log.warn("No payment provider configured; intent {} closed locally only", p.getStripePaymentId());
                } else if (!gateway.cancelIntent(p.getStripePaymentId())) {
                    log.warn("Intent {} could not be cancelled; a late success will be refunded",
                            p.getStripePaymentId());
                }
                p.setStatus(PaymentStatus.FAILED);
                p.setFailureReason("Booking cancelled before payment was completed");
                settle(p, p.getStripePaymentId(), PaymentTransaction.Status.FAILED, p.getFailureReason());
            }
        });
    }

    // ---------------------------------------------------------------- queries and helpers

    @Transactional(readOnly = true)
    public Optional<Payment> find(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId);
    }

    /** The booking's payment with its full provider transaction history. */
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> forBooking(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId).map(p -> {
            List<PaymentTransaction> recorded = transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(p.getPaymentId());
            return PaymentMapper.toResponse(p, false, recorded.stream().map(PaymentMapper::toResponse).toList(),
                    PaymentMapper.receivedMethods(p, recorded));
        });
    }

    @Transactional(readOnly = true)
    public List<PaymentResponse> recent() {
        List<Payment> recent = payments.findTop300ByOrderByCreatedAtDesc();
        // One query for every listed payment's transactions, for the methods each was paid by.
        Map<UUID, List<PaymentTransaction>> recorded = transactions
                .findByPayment_PaymentIdInOrderByCreatedAtAsc(recent.stream().map(Payment::getPaymentId).toList())
                .stream().collect(Collectors.groupingBy(t -> t.getPayment().getPaymentId()));
        return recent.stream().map(p -> PaymentMapper.toResponse(p, true, null,
                PaymentMapper.receivedMethods(p, recorded.getOrDefault(p.getPaymentId(), List.of())))).toList();
    }

    /** The intent or charge transaction recorded for {@code intentId} on this payment, if we created it. */
    private PaymentTransaction intentTransaction(Payment p, String intentId) {
        return intentId == null ? null : transactions
                .findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(p.getPaymentId(), intentId)
                .filter(t -> t.getKind() != PaymentTransaction.Kind.REFUND).orElse(null);
    }

    private int attempts(Payment p, PaymentTransaction.Kind kind) {
        return p.getPaymentId() == null ? 0 : (int) transactions.countByPayment_PaymentIdAndKind(p.getPaymentId(), kind);
    }

    private static PaymentTransaction.Status status(ChargeResult r) {
        return switch (r.outcome()) {
            case SUCCEEDED -> PaymentTransaction.Status.SUCCESS;
            case PENDING -> PaymentTransaction.Status.PENDING;
            case FAILED -> PaymentTransaction.Status.FAILED;
        };
    }

    /** Audit trail: one row per provider call (SDS 6.2, 8.3). */
    private void record(Payment p, PaymentTransaction.Kind kind, String ref, BigDecimal amount,
                        PaymentTransaction.Status status, String failureReason) {
        record(p, kind, ref, null, amount, status, failureReason);
    }

    private void record(Payment p, PaymentTransaction.Kind kind, String ref, String intentRef, BigDecimal amount,
                        PaymentTransaction.Status status, String failureReason) {
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(kind);
        t.setProviderRef(ref);
        t.setIntentRef(intentRef);
        t.setAmount(amount);
        t.setStatus(status);
        t.setFailureReason(failureReason);
        t.setCreatedAt(LocalDateTime.now(clock));
        transactions.save(t);
    }

    /** Records the outcome of an intent's transaction (created when the intent was created). */
    private void settle(Payment p, String intentId, PaymentTransaction.Status status, String failureReason) {
        transactions.findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(p.getPaymentId(), intentId).ifPresent(t -> {
            if (t.getStatus() != PaymentTransaction.Status.SUCCESS) {
                t.setStatus(status);
                t.setFailureReason(failureReason);
            }
        });
    }

    private Payment paid(Booking b) {
        Payment p = payments.findByBookingIdForUpdate(b.getBookingId())
                .orElseThrow(() -> new IllegalStateException("Active booking " + b.getBookingId() + " has no payment"));
        if (p.getStatus() != PaymentStatus.SUCCESS) {
            throw new IllegalStateException("Payment for booking " + b.getBookingId() + " is " + p.getStatus());
        }
        return p;
    }

    private static void requireOwner(Booking b, AuthUser user) {
        if (!b.getUser().getUserId().equals(user.id())) {
            if (user.isAdmin()) {
                throw ApiException.forbidden();
            }
            throw ApiException.notFound("Booking");
        }
    }

    private static Map<String, String> metadata(Booking b, String purpose) {
        return Map.of("bookingId", String.valueOf(b.getBookingId()), "bookingReference", b.getReference(),
                "purpose", purpose);
    }

    private static UUID parseId(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
