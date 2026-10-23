package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires one unpaid booking in its own transaction. A separate bean so that {@link BookingService#expireUnpaid}
 * reaches it through the transactional proxy: each booking commits or rolls back on its own.
 */
@Service
public class UnpaidBookingExpiry {

    private final BookingRepository bookings;
    private final PaymentService payments;
    private final Clock clock;

    public UnpaidBookingExpiry(BookingRepository bookings, PaymentService payments, Clock clock) {
        this.bookings = bookings;
        this.payments = payments;
        this.clock = clock;
    }

    /**
     * Cancels the booking if, once its row is locked, it is still PENDING and was created before
     * {@code createdBefore}; closes its open payment intent. Returns whether it was expired. The re-check matters:
     * a payment or cancellation may have changed the booking after it was selected as a candidate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(UUID bookingId, LocalDateTime createdBefore) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElse(null);
        if (b == null || b.getStatus() != BookingStatus.PENDING || !b.getCreatedAt().isBefore(createdBefore)) {
            return false;
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(LocalDateTime.now(clock));
        payments.cancelOpenIntent(b);
        return true;
    }
}
