package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PaymentEvents.BalancePaid;
import com.scooterrentalkandy.spring.services.PaymentEvents.InitialPaymentSucceeded;
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
 * <p>The provider is optional: without one, payment operations answer 503 and nothing is simulated. Provider
 * calls with side effects carry idempotency keys derived from the booking and attempt number, so a retry after a
 * rolled-back transaction gets the provider's original answer instead of a second charge.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String INITIAL = "INITIAL";
    static final String BALANCE = "BALANCE";
    private static final Set<String> HANDLED = Set.of(PaymentGateway.Event.SUCCEEDED, PaymentGateway.Event.FAILED);

    private final PaymentRepository payments;
    private final PaymentTransactionRepository transactions;
    private final PaymentProviderEventRepository providerEvents;
    private final BookingRepository bookings;
    private final ObjectProvider<PaymentGateway> gateways;
    private final PricingService pricing;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final String currency;

    public PaymentService(PaymentRepository payments, PaymentTransactionRepository transactions,
                          PaymentProviderEventRepository providerEvents, BookingRepository bookings,
                          ObjectProvider<PaymentGateway> gateways, PricingService pricing,
                          ApplicationEventPublisher events, Clock clock, AppProperties props) {
        this.payments = payments;
        this.transactions = transactions;
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

        Payment p = payments.findByBooking_BookingId(bookingId).orElse(null);
        if (p == null) {
            p = new Payment();
            p.setBooking(b);
            p.setMethod(PaymentMethod.CARD);
            p.setProvider(gateway.name());
        } else if (p.getStatus() == PaymentStatus.SUCCESS || p.getStatus() == PaymentStatus.REFUNDED) {
            throw ApiException.conflict("This booking has already been paid");
        } else if (p.getStripePaymentId() != null) {
            replace(gateway, p, p.getStripePaymentId());
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
        Payment p = payments.findByBooking_BookingId(bookingId).orElse(null);
        if (b.getStatus() != BookingStatus.ACTIVE || p == null || p.getBalanceDue() == null) {
            throw ApiException.conflict("This booking has no balance due");
        }
        PaymentGateway gateway = gateway();
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

    /**
     * SDS 4.2 step 12: the provider's webhook. Each event is processed once (its id is stored); stale or
     * repeated deliveries are ignored. A success counts only if the intent, amount and currency are the ones this
     * booking expects.
     */
    @Transactional
    public void handleWebhook(String payload, String signature) {
        PaymentGateway gateway = gateway();
        PaymentGateway.Event e;
        try {
            e = gateway.parseWebhook(payload, signature);
        } catch (IllegalArgumentException ex) {
            throw ApiException.badRequest("Invalid webhook: " + ex.getMessage());
        }
        if (!HANDLED.contains(e.type())) {
            return;
        }
        UUID bookingId = parseId(e.metadata().get("bookingId"));
        Payment p = bookingId == null ? null : payments.findByBookingIdForUpdate(bookingId).orElse(null);
        if (providerEvents.existsByProviderAndEventId(gateway.name(), e.id())) {
            log.info("Webhook event {} already processed", e.id());
            return;
        }
        if (p == null) {
            log.warn("Webhook for unknown booking {} ignored", e.metadata().get("bookingId"));
        } else {
            String purpose = e.metadata().get("purpose");
            if (INITIAL.equals(purpose) && e.intentId().equals(p.getStripePaymentId())) {
                onInitial(p, e);
            } else if (BALANCE.equals(purpose) && e.intentId().equals(p.getBalanceIntentId())) {
                onBalance(p, e);
            } else {
                log.info("Stale webhook for intent {} ignored", e.intentId());
            }
        }
        providerEvents.save(new PaymentProviderEvent(gateway.name(), e.id(), e.type(), LocalDateTime.now(clock)));
    }

    private void onInitial(Payment p, PaymentGateway.Event e) {
        if (p.getStatus() != PaymentStatus.PENDING && p.getStatus() != PaymentStatus.FAILED) {
            return; // already handled
        }
        if (e.succeeded() && !matches(e, p.getAmount())) {
            mismatch(p, e);
        } else if (e.succeeded()) {
            p.setStatus(PaymentStatus.SUCCESS);
            p.setPaidAt(LocalDateTime.now(clock));
            p.setPaymentMethodRef(e.paymentMethodRef());
            p.setCardLast4(e.cardLast4());
            p.setFailureReason(null);
            settle(p, e.intentId(), PaymentTransaction.Status.SUCCESS, null);
            events.publishEvent(new InitialPaymentSucceeded(p.getBooking().getBookingId()));
        } else {
            p.setStatus(PaymentStatus.FAILED);
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        }
    }

    private void onBalance(Payment p, PaymentGateway.Event e) {
        if (p.getBalanceDue() == null) {
            return; // already handled
        }
        if (e.succeeded() && !matches(e, p.getBalanceDue())) {
            mismatch(p, e);
        } else if (e.succeeded()) {
            settle(p, e.intentId(), PaymentTransaction.Status.SUCCESS, null);
            p.setAmount(p.getAmount().add(p.getBalanceDue()));
            p.setBalanceDue(null);
            p.setBalanceIntentId(null);
            p.setPaidAt(LocalDateTime.now(clock));
            p.setPaymentMethodRef(e.paymentMethodRef());
            p.setCardLast4(e.cardLast4());
            p.setFailureReason(null);
            events.publishEvent(new BalancePaid(p.getBooking().getBookingId()));
        } else {
            p.setFailureReason(e.failureReason());
            settle(p, e.intentId(), PaymentTransaction.Status.FAILED, e.failureReason());
        }
    }

    /** The provider's amount and currency are the ones stored when the intent was created. */
    private boolean matches(PaymentGateway.Event e, BigDecimal expected) {
        return e.amount() != null && e.amount().compareTo(expected) == 0 && currency.equalsIgnoreCase(e.currency());
    }

    /** A signed success that does not match what this booking expects is not accepted as payment. */
    private void mismatch(Payment p, PaymentGateway.Event e) {
        log.error("Payment intent {} for booking {} succeeded with {} {} but {} was expected; not accepted",
                e.intentId(), p.getBooking().getBookingId(), e.currency(), e.amount(), currency);
        String reason = "The provider reported a different amount or currency";
        settle(p, e.intentId(), PaymentTransaction.Status.FAILED, reason);
        p.setFailureReason(reason);
        if (e.intentId().equals(p.getStripePaymentId())) {
            p.setStatus(PaymentStatus.FAILED);
        }
    }

    /**
     * SDS 4.3 step 11 chargeRemainingBalance: charges {@code remaining} to the saved payment method and adds it
     * to the booking's payment. On failure the balance is recorded as due on the same row. Returns success.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean chargeRemainingBalance(Booking b, BigDecimal remaining) {
        PaymentGateway gateway = gateway();
        Payment p = paid(b);
        int attempt = attempts(p, PaymentTransaction.Kind.BALANCE) + 1;
        PaymentGateway.ChargeResult r = gateway.chargeSaved(b.getUser().getStripeCustomerId(),
                p.getPaymentMethodRef(), remaining, currency, "Scooter Rent Kandy balance " + b.getReference(),
                metadata(b, BALANCE), "booking-" + b.getBookingId() + "-balance-charge-" + attempt);
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
        p.setFailureReason(r.outcome() == PaymentGateway.Outcome.PENDING ? "The payment is still being processed"
                : r.failureReason());
        return false;
    }

    /**
     * Only for bookings paid under the earlier prepaid/deposit model, which can have paid more than the final
     * cost: the difference is refunded and the payment amount becomes the final cost.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refundExcess(Booking b, BigDecimal excess) {
        PaymentGateway gateway = gateway();
        Payment p = paid(b);
        String intentId = p.getStripePaymentId();
        PaymentGateway.ChargeResult r = gateway.refund(intentId, excess, currency,
                "Final cost below amount paid for " + b.getReference(), "refund-" + intentId + "-excess");
        record(p, PaymentTransaction.Kind.REFUND, r.reference(), intentId, excess, status(r), r.failureReason());
        if (r.outcome() != PaymentGateway.Outcome.FAILED) {
            p.setAmount(p.getAmount().subtract(excess));
            p.setFailureReason(null);
        } else {
            p.setFailureReason("Refund of " + currency + " " + excess + " failed: " + r.failureReason());
        }
    }

    /** Refunds the whole payment (a permitted cancellation). Returns the payment, or null if nothing was paid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Payment refundInFull(Booking b, String reason) {
        Payment p = payments.findByBookingIdForUpdate(b.getBookingId()).orElse(null);
        if (p == null || p.getStatus() != PaymentStatus.SUCCESS) {
            return null;
        }
        String intentId = p.getStripePaymentId();
        PaymentGateway.ChargeResult r = gateway().refund(intentId, p.getAmount(), currency, reason,
                "refund-" + intentId + "-full");
        if (r.outcome() == PaymentGateway.Outcome.FAILED) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Refund failed: " + r.failureReason());
        }
        record(p, PaymentTransaction.Kind.REFUND, r.reference(), intentId, p.getAmount(), status(r), null);
        p.setStatus(PaymentStatus.REFUNDED);
        return p;
    }

    /**
     * A booking cancelled before payment: close its open intent so it can no longer be paid. If the provider can
     * no longer cancel it (the customer may be paying right now), a later success is refunded by the booking
     * lifecycle. Without a configured provider the intent cannot be paid either; it is closed here only.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cancelOpenIntent(Booking b) {
        payments.findByBookingIdForUpdate(b.getBookingId()).ifPresent(p -> {
            if (p.getStatus() == PaymentStatus.PENDING) {
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

    @Transactional(readOnly = true)
    public Optional<Payment> find(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId);
    }

    /** The booking's payment with its full provider transaction history. */
    @Transactional(readOnly = true)
    public Optional<PaymentResponse> forBooking(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId).map(p -> PaymentMapper.toResponse(p, false,
                transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(p.getPaymentId()).stream()
                        .map(PaymentMapper::toResponse).toList()));
    }

    private int attempts(Payment p, PaymentTransaction.Kind kind) {
        return p.getPaymentId() == null ? 0 : (int) transactions.countByPayment_PaymentIdAndKind(p.getPaymentId(), kind);
    }

    private static PaymentTransaction.Status status(PaymentGateway.ChargeResult r) {
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

    @Transactional(readOnly = true)
    public List<PaymentResponse> recent() {
        return payments.findTop300ByOrderByCreatedAtDesc().stream().map(p -> PaymentMapper.toResponse(p, true)).toList();
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
