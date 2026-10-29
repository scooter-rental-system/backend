package com.scooterrentalkandy.spring.services;


import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.ContractResponse;
import com.scooterrentalkandy.spring.dto.BookingDtos.CreateBookingRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.GearSelectionRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.Quote;
import com.scooterrentalkandy.spring.dto.BookingDtos.QuoteRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.RentalBreakdown;
import com.scooterrentalkandy.spring.dto.BookingDtos.SignContractRequest;
import com.scooterrentalkandy.spring.dto.BookingDtos.Stage;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.BookingMapper;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Contract;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.PaymentEvents.BalancePaid;
import com.scooterrentalkandy.spring.services.PaymentEvents.InitialPaymentSucceeded;
import com.scooterrentalkandy.spring.services.PricingService.FinalBill;
import com.scooterrentalkandy.spring.services.PricingService.GearSelection;
import com.scooterrentalkandy.spring.services.PricingService.InitialCharge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * PENDING (booked, contract signed, initial charge being paid) → ACTIVE (paid; rental runs from startTime)
 * → COMPLETED (the customer ended it and the final total was collected), or CANCELLED.
 */
@Service
public class BookingService {

    public static final int MAX_ADVANCE_DAYS = 365;
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm");

    private final BookingRepository bookings;
    private final ScooterRepository scooters;
    private final UserRepository users;
    private final GearService gearService;
    private final PricingService pricing;
    private final ContractService contracts;
    private final PaymentService payments;
    private final BillingService billing;
    private final GpsService gps;
    private final MaintenanceService maintenance;
    private final RepairService repairs;
    private final NotificationService notifications;
    private final UnpaidBookingExpiry unpaidExpiry;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final String currency;

    public BookingService(BookingRepository bookings, ScooterRepository scooters, UserRepository users,
                          GearService gearService, PricingService pricing, ContractService contracts, PaymentService payments,
                          BillingService billing, GpsService gps, MaintenanceService maintenance,
                          RepairService repairs, NotificationService notifications, UnpaidBookingExpiry unpaidExpiry,
                          PlatformTransactionManager transactionManager, Clock clock, AppProperties props) {
        this.bookings = bookings;
        this.scooters = scooters;
        this.users = users;
        this.gearService = gearService;
        this.pricing = pricing;
        this.contracts = contracts;
        this.payments = payments;
        this.billing = billing;
        this.gps = gps;
        this.maintenance = maintenance;
        this.repairs = repairs;
        this.notifications = notifications;
        this.unpaidExpiry = unpaidExpiry;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.currency = props.billing().currency();
    }

    // ---------------------------------------------------------------- booking

    @Transactional(readOnly = true)
    public Quote quote(QuoteRequest req) {
        Scooter scooter = scooters.findById(req.scooterId()).orElseThrow(() -> ApiException.notFound("Scooter"));
        InitialCharge c = pricing.initialCharge(scooter, resolveGear(req.gear(), false));
        return new Quote(scooter.getScooterId(), currency, c.dailyRate(), c.hourlyRate(), c.perKmRate(), c.gear(),
                c.gearCost(), c.total());
    }

    /** SDS 4.2 steps 1-6: validate availability and create the booking as PENDING. */
    @Transactional
    public BookingResponse create(UUID customerId, CreateBookingRequest req) {
        LocalDateTime now = now();
        if (req.startTime().isBefore(now.minusMinutes(1))) {
            throw ApiException.badRequest("Start time cannot be in the past");
        }
        if (req.startTime().isAfter(now.plusDays(MAX_ADVANCE_DAYS))) {
            throw ApiException.badRequest("Bookings open up to " + MAX_ADVANCE_DAYS + " days ahead");
        }
        User customer = users.findById(customerId).orElseThrow(() -> ApiException.notFound("User"));
        // Lock the scooter row so two customers can't take the same scooter concurrently.
        Scooter scooter = scooters.findByIdForUpdate(req.scooterId())
                .orElseThrow(() -> ApiException.notFound("Scooter"));
        if (scooter.isDeleted()) {
            throw ApiException.badRequest("This scooter is no longer available for rent");
        }
        if (scooter.getStatus() != ScooterStatus.AVAILABLE
                || bookings.existsByScooter_ScooterIdAndStatusIn(scooter.getScooterId(), BookingStatus.BLOCKING)) {
            throw ApiException.conflict("This scooter is not available right now; choose another scooter");
        }
        List<GearSelection> gear = resolveGear(req.gear(), true);

        Booking b = new Booking();
        b.setReference(References.next("BK"));
        b.setUser(customer);
        b.setScooter(scooter);
        b.setStartTime(req.startTime().isBefore(now) ? now : req.startTime());
        b.setStatus(BookingStatus.PENDING);
        b.setPickupLocation(req.pickupLocation().trim());
        b.setNotes(blankToNull(req.notes()));
        for (GearSelection g : gear) {
            BookingGear line = new BookingGear();
            line.setGearItem(g.item());
            line.setQuantity(g.quantity());
            b.addGear(line);
        }
        bookings.save(b);
        contracts.createFor(b);
        return toResponse(b);
    }

    @Transactional
    public ContractResponse signContract(UUID bookingId, AuthUser user, SignContractRequest req, String ip) {
        Booking b = findOwned(bookingId, user);
        if (!b.getUser().getUserId().equals(user.id())) {
            throw ApiException.forbidden();
        }
        if (!req.agree()) {
            throw ApiException.badRequest("You must agree to the rental terms");
        }
        return toResponse(contracts.sign(b, req.signedName(), ip));
    }

    @Transactional(readOnly = true)
    public ContractResponse contract(UUID bookingId, AuthUser user) {
        findOwned(bookingId, user);
        return toResponse(contracts.forBooking(bookingId));
    }

    // ---------------------------------------------------------------- payment events

    /** SDS 4.2 steps 13-14: a successful initial payment activates the booking; the rental is under way. */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onInitialPayment(InitialPaymentSucceeded event) {
        Booking b = find(event.bookingId());
        if (b.getStatus() != BookingStatus.PENDING || !b.isContractSigned()) {
            // Paid after the booking expired or was cancelled: give the money back.
            boolean refunding = payments.refundLatePayment(b, "Payment received for booking " + b.getReference()
                    + " after it was " + b.getStatus().name().toLowerCase());
            notifications.notify(b.getUser(), "Payment refunded: " + b.getReference(), refunding
                    ? "Your payment arrived after the booking had ended, so it is being refunded in full."
                    : "Your payment arrived after the booking had ended. We could not refund it automatically; "
                            + "our team will refund it in full.");
            if (!refunding) {
                notifications.notifyAdmins("Refund needed " + b.getReference(), "A payment for "
                        + b.getReference() + " arrived after the booking ended and could not be refunded "
                        + "automatically. Refund it from the payment provider's dashboard.");
            }
            return;
        }
        b.setStatus(BookingStatus.ACTIVE);
        Scooter s = b.getScooter();
        s.setStatus(ScooterStatus.RENTED);
        notifications.notify(b.getUser(), "Booking confirmed: " + b.getReference(),
                "Your " + s.getModel() + " (" + s.getPlateNumber() + ") is yours from " + b.getStartTime().format(WHEN)
                        + ". You were charged " + currency + " " + payments.find(b.getBookingId()).map(Payment::getAmount)
                        .orElse(BigDecimal.ZERO) + " now; the rest is billed by the hour and kilometre when you end "
                        + "the rental.");
        notifications.notifyAdmins("New booking " + b.getReference(),
                b.getUser().getFullName() + " booked " + s.getCode() + " from " + b.getStartTime().format(WHEN)
                        + ".");
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onBalancePaid(BalancePaid event) {
        Booking b = find(event.bookingId());
        if (b.getStatus() == BookingStatus.ACTIVE && b.getEndedAt() != null) {
            finishRental(b);
        }
    }

    // ---------------------------------------------------------------- ending a rental (SDS 4.3, E2)

    /** The outcome of collecting the balance: the completed booking, or why the balance could not be charged. */
    private record Settlement(BookingResponse booking, String unpaidMessage) {
    }

    /**
     * The customer (or an admin on their behalf) ends the rental, in two transactions. The first fixes the end
     * time, the GPS distance and the final total, and issues the final invoice; it is committed before any money
     * moves, so every later attempt charges exactly the same amount. The second charges the remaining balance (or
     * refunds an excess) and completes the booking. If the charge is declined the booking stays ACTIVE with the
     * balance due (402) and calling this again retries the same amount. If the provider cannot be reached, only
     * the second transaction rolls back and a retry repeats the charge with the same idempotency key.
     */
    public BookingResponse complete(UUID bookingId, AuthUser user) {
        transaction.executeWithoutResult(status -> freezeSettlement(bookingId, user));
        Settlement settlement = transaction.execute(status -> collectBalance(bookingId, user));
        if (settlement.unpaidMessage() != null) {
            throw new ApiException(HttpStatus.PAYMENT_REQUIRED, settlement.unpaidMessage());
        }
        return settlement.booking();
    }

    /**
     * Only the first step of {@link #complete}: fixes the end time, the GPS distance and the final total and issues
     * the final invoice, without charging or refunding anything. Used by the viva demo profile, whose rentals have
     * no payment to settle against; the booking stays ACTIVE with the final total pending.
     */
    public BookingResponse endWithoutCollecting(UUID bookingId, AuthUser user) {
        transaction.executeWithoutResult(status -> freezeSettlement(bookingId, user));
        return transaction.execute(status -> toResponse(find(bookingId)));
    }

    private void freezeSettlement(UUID bookingId, AuthUser user) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        Booking b = findOwned(bookingId, user);
        if (b.getStatus() != BookingStatus.ACTIVE) {
            throw ApiException.conflict("Only an active rental can be ended (status " + b.getStatus() + ")");
        }
        if (b.getEndedAt() == null) {
            LocalDateTime end = now();
            if (end.isBefore(b.getStartTime())) {
                throw ApiException.conflict("This rental starts at " + b.getStartTime().format(WHEN)
                        + "; cancel the booking instead of ending it");
            }
            BigDecimal km = gps.rentalDistanceKm(b.getBookingId(), b.getStartTime(), end);
            // The rental subtotal less the repair reimbursement approved so far (RESOLVED reports), never below zero.
            FinalBill bill = pricing.finalBill(b.getScooter(), PricingService.selectionsOf(b), b.getStartTime(), end,
                    km, repairs.approvedReimbursement(b.getBookingId()));
            // Frozen settlement: kept on the ACTIVE booking until the balance is collected (SDS: endTime and
            // totalCost stay NULL until COMPLETED).
            b.setEndedAt(end);
            b.setPendingDistanceKm(bill.distanceKm());
            b.setPendingTotalCost(bill.total());
            billing.issueFinalInvoice(b, bill);
        }
    }

    private Settlement collectBalance(UUID bookingId, AuthUser user) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        Booking b = findOwned(bookingId, user);
        if (b.getStatus() != BookingStatus.ACTIVE || b.getEndedAt() == null) {
            throw ApiException.conflict("Only an active rental can be ended (status " + b.getStatus() + ")");
        }
        Payment p = payments.find(b.getBookingId()).orElseThrow(() -> new IllegalStateException("No payment"));
        BigDecimal remaining = b.getPendingTotalCost().subtract(p.getAmount());
        if (remaining.signum() > 0) {
            if (!payments.chargeRemainingBalance(b, remaining)) {
                Payment failed = payments.find(b.getBookingId()).orElseThrow();
                notifications.notify(b.getUser(), "Payment needed: " + b.getReference(),
                        "Your rental ended but the remaining " + currency + " " + remaining
                                + " could not be charged (" + failed.getFailureReason()
                                + "). Please pay it online to finish your rental.");
                return new Settlement(null, "Rental ended, but the remaining balance of " + currency + " "
                        + remaining + " could not be charged: " + failed.getFailureReason()
                        + ". Pay it online to finish.");
            }
        } else if (remaining.signum() < 0) {
            payments.refundExcess(b, remaining.negate());
        }
        finishRental(b);
        return new Settlement(toResponse(b), null);
    }

    private void finishRental(Booking b) {
        // The settlement becomes final: move it into the SDS fields.
        b.setEndTime(b.getEndedAt());
        b.setDistanceKm(b.getPendingDistanceKm());
        b.setTotalCost(b.getPendingTotalCost());
        b.setEndedAt(null);
        b.setPendingDistanceKm(null);
        b.setPendingTotalCost(null);
        b.setStatus(BookingStatus.COMPLETED);
        b.setDropLocation(gps.dropLocation(b.getBookingId(), b.getScooter()));
        Scooter s = b.getScooter();
        s.setStatus(ScooterStatus.AVAILABLE);
        s.setTotalMileage(s.getTotalMileage().add(b.getDistanceKm()));
        maintenance.scheduleServiceIfDue(s);
        notifications.notify(b.getUser(), "Rental completed: " + b.getReference(),
                "Thanks for riding with us! " + PricingService.billableHours(b.getStartTime(), b.getEndTime())
                        + " hour(s), " + b.getDistanceKm() + " km. Total " + currency + " " + b.getTotalCost()
                        + ". Your invoice is in your booking.");
    }

    // ---------------------------------------------------------------- cancellation (approved E3 rules)

    @Transactional
    public BookingResponse cancel(UUID bookingId, AuthUser user) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        Booking b = findOwned(bookingId, user);
        switch (b.getStatus()) {
            case PENDING -> {
                markCancelled(b);
                payments.cancelOpenIntent(b);
            }
            case ACTIVE -> {
                // Only before the rental starts: before startTime and with no GPS logs. Full refund.
                if (!now().isBefore(b.getStartTime()) || gps.hasRentalLogs(b.getBookingId())) {
                    throw ApiException.conflict("This rental has started and can no longer be cancelled; end it instead");
                }
                markCancelled(b);
                Payment refund = payments.refundInFull(b, "Booking " + b.getReference() + " cancelled");
                b.getScooter().setStatus(ScooterStatus.AVAILABLE);
                billing.voidEarlierRentalInvoice(b.getBookingId());
                BigDecimal refunded = refund == null ? BigDecimal.ZERO : refund.getAmount();
                notifications.notify(b.getUser(), "Booking cancelled: " + b.getReference(),
                        "Your booking has been cancelled. A refund of " + currency + " " + refunded
                                + " has been issued.");
                notifications.notifyAdmins("Booking cancelled " + b.getReference(),
                        b.getUser().getFullName() + " cancelled their booking for " + b.getScooter().getCode());
            }
            default -> throw ApiException.conflict("A booking that is " + b.getStatus() + " cannot be cancelled");
        }
        return toResponse(b);
    }

    /**
     * Releases unpaid bookings so they stop holding the scooter and gear. Deliberately not transactional: each
     * candidate is expired in its own transaction, so one failure is logged and retried on the next run without
     * undoing the others. Returns how many were expired.
     */
    public int expireUnpaid(LocalDateTime createdBefore) {
        int expired = 0;
        for (UUID id : bookings.findIdsByStatusAndCreatedAtBefore(BookingStatus.PENDING, createdBefore)) {
            try {
                if (unpaidExpiry.expire(id, createdBefore)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("Could not expire unpaid booking {}; it will be retried on the next run", id, e);
            }
        }
        return expired;
    }

    // ---------------------------------------------------------------- queries

    @Transactional(readOnly = true)
    public List<BookingResponse> listForCustomer(UUID customerId) {
        return bookings.findByUser_UserIdOrderByCreatedAtDesc(customerId).stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public BookingResponse get(UUID bookingId, AuthUser user) {
        return toResponse(findOwned(bookingId, user));
    }

    @Transactional(readOnly = true)
    public List<BookingResponse> listAll(BookingStatus status) {
        var list = status == null ? bookings.findAllByOrderByCreatedAtDesc()
                : bookings.findByStatusOrderByStartTimeAsc(status);
        return list.stream().map(this::toResponse).toList();
    }

    // ---------------------------------------------------------------- helpers

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private void markCancelled(Booking b) {
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(now());
    }

    /**
     * Merges the requested lines per item and checks each against the units still available. When
     * {@code forBooking}, every item is row-locked first, in ascending ID order (after the scooter lock taken by
     * the caller), so two bookings can neither reserve the same last units nor deadlock; the locks are held
     * until the booking commits. A quote only reads (its read-only transaction cannot lock).
     */
    private List<GearSelection> resolveGear(List<GearSelectionRequest> requested, boolean forBooking) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        Map<UUID, Integer> merged = new LinkedHashMap<>();
        requested.forEach(r -> merged.merge(r.gearItemId(), r.quantity(), Integer::sum));
        Map<UUID, GearItem> locked = new HashMap<>();
        if (forBooking) {
            merged.keySet().stream().sorted().forEach(id -> locked.put(id, gearService.findForBooking(id)));
        }
        List<GearSelection> result = new ArrayList<>();
        merged.forEach((id, qty) -> {
            GearItem item = forBooking ? locked.get(id) : gearService.find(id);
            if (!item.isActive()) {
                throw ApiException.badRequest(item.getName() + " is not available for rent");
            }
            int available = gearService.available(item);
            if (qty > available) {
                throw ApiException.conflict("Only " + available + " x " + item.getName() + " available right now");
            }
            result.add(new GearSelection(item, qty));
        });
        return result;
    }

    private Booking find(UUID id) {
        return bookings.findWithDetailsByBookingId(id).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private Booking findOwned(UUID id, AuthUser user) {
        Booking b = find(id);
        if (!user.isAdmin() && !b.getUser().getUserId().equals(user.id())) {
            // Don't reveal that someone else's booking exists.
            throw ApiException.notFound("Booking");
        }
        return b;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    BookingResponse toResponse(Booking b) {
        Payment p = payments.find(b.getBookingId()).orElse(null);
        BigDecimal initialCharge = pricing.initialCharge(b.getScooter(), PricingService.selectionsOf(b)).total();
        LocalDateTime ended = b.getEndTime() != null ? b.getEndTime() : b.getEndedAt();
        Long hours = ended == null ? null : PricingService.billableHours(b.getStartTime(), ended);
        return BookingMapper.toResponse(b, p, stage(b, p), hours, breakdown(b, ended), initialCharge);
    }

    /** The pricing rule applied to an ended rental (the settled distance, or the frozen one while a balance is due). */
    private static RentalBreakdown breakdown(Booking b, LocalDateTime ended) {
        BigDecimal km = b.getDistanceKm() != null ? b.getDistanceKm() : b.getPendingDistanceKm();
        if (ended == null || km == null) {
            return null;
        }
        PricingService.ScooterTime time = PricingService.scooterTime(b.getStartTime(), ended);
        PricingService.Distance d = PricingService.distance(time.days(), km);
        return new RentalBreakdown(time.days(), time.extraHours(), d.includedKm(), d.excessKm());
    }

    private Stage stage(Booking b, Payment p) {
        return switch (b.getStatus()) {
            case PENDING -> p != null && p.getStatus() == PaymentStatus.FAILED ? Stage.PAYMENT_FAILED
                    : Stage.AWAITING_PAYMENT;
            case ACTIVE -> b.getEndedAt() != null ? Stage.BALANCE_DUE
                    : now().isBefore(b.getStartTime()) ? Stage.UPCOMING : Stage.IN_PROGRESS;
            case COMPLETED -> Stage.COMPLETED;
            case CANCELLED -> p != null && p.getStatus() == PaymentStatus.REFUNDED ? Stage.REFUNDED
                    : Stage.CANCELLED;
        };
    }

    private ContractResponse toResponse(Contract c) {
        return BookingMapper.toResponse(c);
    }
}

