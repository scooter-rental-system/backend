package com.scooterrentalkandy.spring.demo;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.BookingDtos.BookingResponse;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.services.BookingService;
import com.scooterrentalkandy.spring.services.NotificationService;
import com.scooterrentalkandy.spring.services.PaymentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Viva demo lifecycle (profile {@code demo} only, see {@link DemoMode}). A demo rental is a booking with no payment
 * record: nothing is charged, refunded or recorded as paid, and no provider is involved. Each step is announced to
 * the customer and logged to the admins as "VIVA DEMO", so the records say what happened.
 *
 * <ul>
 *   <li>activate: a PENDING booking with a signed agreement and no payment becomes ACTIVE, starting now (or at its
 *       start time if that has passed), so the normal GPS and repair rules apply unchanged;</li>
 *   <li>end: the real end-of-rental step (GPS distance, PricingService final bill, final invoice) without charging;</li>
 *   <li>close: releases the scooter and gear afterwards: the booking is cancelled and its final invoice voided,
 *       because no money was collected for it.</li>
 * </ul>
 */
@Service
@Profile(DemoMode.PROFILES)
public class DemoLifecycleService {

    private static final Logger log = LoggerFactory.getLogger(DemoLifecycleService.class);
    static final String NO_PAYMENT = "No payment was taken: this is a viva demo rental.";

    private final BookingRepository bookings;
    private final InvoiceRepository invoices;
    private final PaymentService payments;
    private final BookingService bookingService;
    private final NotificationService notifications;
    private final Clock clock;

    public DemoLifecycleService(BookingRepository bookings, InvoiceRepository invoices, PaymentService payments,
                                BookingService bookingService, NotificationService notifications, Clock clock) {
        this.bookings = bookings;
        this.invoices = invoices;
        this.payments = payments;
        this.bookingService = bookingService;
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional
    public BookingResponse activate(UUID bookingId, AuthUser admin) {
        Booking b = lock(bookingId);
        if (b.getStatus() != BookingStatus.PENDING) {
            throw ApiException.conflict("Only a booking awaiting payment can be activated for the demo (status "
                    + b.getStatus() + ")");
        }
        if (!b.isContractSigned()) {
            throw ApiException.conflict("The customer must sign the rental agreement before the demo activation");
        }
        requireNoPayment(b);
        // Skips only the payment: the booking is ready for pickup, and the rental starts through the normal handover
        // (with the starting odometer) and the customer's start confirmation. The planned pickup time is kept.
        b.setStatus(BookingStatus.READY_FOR_PICKUP);
        log.warn("VIVA DEMO: booking {} marked ready for pickup without payment by admin {}", b.getReference(),
                admin.id());
        notifications.notify(b.getUser(), "Viva demo: ready for pickup " + b.getReference(),
                "Your " + b.getScooter().getModel() + " (" + b.getScooter().getPlateNumber()
                        + ") was confirmed by our team in viva demo mode. " + BookingService.RENTAL_STARTS + " "
                        + NO_PAYMENT);
        notifications.notifyAdmins("VIVA DEMO: ready for pickup " + b.getReference(),
                "Confirmed without payment in viva demo mode; confirm the handover to continue. " + NO_PAYMENT);
        return bookingService.get(bookingId, admin);
    }

    /** Ends a demo rental in progress with the ending odometer: the real final bill and invoice, nothing charged. */
    public BookingResponse end(UUID bookingId, AuthUser admin, BigDecimal endOdometerKm) {
        Booking b = demoRental(bookingId);
        if (b.getStatus() != BookingStatus.ACTIVE) {
            throw ApiException.conflict("This demo booking has not started yet");
        }
        if (b.getEndedAt() != null) {
            throw ApiException.conflict("This demo rental has already ended");
        }
        BookingResponse ended = bookingService.endWithoutCollecting(bookingId, admin, endOdometerKm);
        log.warn("VIVA DEMO: booking {} ended without payment by admin {}", ended.reference(), admin.id());
        notifications.notifyAdmins("VIVA DEMO: rental ended " + ended.reference(), "Final total calculated: "
                + ended.pendingTotalCost() + ". " + NO_PAYMENT);
        return ended;
    }

    /** Releases a demo rental: cancelled, final invoice voided, scooter available again. Nothing else is touched. */
    @Transactional
    public BookingResponse close(UUID bookingId, AuthUser admin) {
        lock(bookingId);
        Booking b = demoRental(bookingId);
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(LocalDateTime.now(clock));
        b.setEndedAt(null);
        b.setPendingDistanceKm(null);
        b.setPendingTotalCost(null);
        b.getScooter().setStatus(ScooterStatus.AVAILABLE);
        invoices.findFirstByBooking_BookingIdAndKind(bookingId, Invoice.Kind.FINAL)
                .ifPresent(inv -> inv.setStatus(Invoice.Status.VOID));
        log.warn("VIVA DEMO: booking {} closed without payment by admin {}", b.getReference(), admin.id());
        notifications.notifyAdmins("VIVA DEMO: rental closed " + b.getReference(),
                "Demo rental cancelled and its final invoice voided; the scooter is available again. " + NO_PAYMENT);
        return bookingService.get(bookingId, admin);
    }

    /** A READY_FOR_PICKUP or ACTIVE booking without a payment record: only demo activation creates one. */
    private Booking demoRental(UUID bookingId) {
        Booking b = bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        boolean held = b.getStatus() == BookingStatus.READY_FOR_PICKUP || b.getStatus() == BookingStatus.ACTIVE;
        if (!held || payments.find(bookingId).isPresent()) {
            throw ApiException.conflict("Only a rental started in viva demo mode can be ended or closed here");
        }
        return b;
    }

    private Booking lock(UUID bookingId) {
        bookings.findByIdForUpdate(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        return bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
    }

    private void requireNoPayment(Booking b) {
        if (payments.find(b.getBookingId()).isPresent()) {
            throw ApiException.conflict("This booking has a payment record, so it goes through the payment provider");
        }
    }
}
