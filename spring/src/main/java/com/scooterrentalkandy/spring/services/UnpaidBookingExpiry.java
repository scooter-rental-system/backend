package com.scooterrentalkandy.spring.services;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expires one unpaid booking in its own transaction. A separate bean so that {@link BookingService#expireUnpaid}
 * reaches it through the transactional proxy: each booking commits or rolls back on its own.
 *
 * <p>QR payments and bank transfers (BE-045) need an admin to check the business account, which can take longer than
 * the usual payment hold. While a receipt submitted within the hold waits for verification, the normal hold is
 * suspended and the booking stays reserved; but only for {@code app.payments.manual-verification-timeout-minutes}
 * (MANUAL_PAYMENT_VERIFICATION_TIMEOUT_MINUTES, 120 by default) from the receipt's submission, so no booking is
 * reserved indefinitely. After that the booking is released, the receipt expires unapproved and the admins are told
 * to refund the money if it arrived. When a receipt is rejected, the usual hold starts again from the rejection, so
 * the customer has the same time to upload a replacement.
 */
@Service
public class UnpaidBookingExpiry {

    private final BookingRepository bookings;
    private final PaymentService payments;
    private final ManualPaymentService transfers;
    private final PaymentReceiptRepository receipts;
    private final Clock clock;
    private final int verificationHoldMinutes;

    public UnpaidBookingExpiry(BookingRepository bookings, PaymentService payments, ManualPaymentService transfers,
                               PaymentReceiptRepository receipts, Clock clock,
                               @Value("${app.payments.manual-verification-timeout-minutes}") int verificationHoldMinutes) {
        this.bookings = bookings;
        this.payments = payments;
        this.transfers = transfers;
        this.receipts = receipts;
        this.clock = clock;
        this.verificationHoldMinutes = verificationHoldMinutes;
    }

    /**
     * Cancels the booking if, once its row is locked, it is still PENDING and its payment hold started before
     * {@code createdBefore} (the hold starts when the booking is created, or again when a transfer receipt is
     * rejected); closes its open payment intent. A receipt waiting for verification keeps the booking until the
     * verification hold runs out instead. Returns whether it was expired. The re-check matters: a payment or
     * cancellation may have changed the booking after it was selected as a candidate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean expire(UUID bookingId, LocalDateTime createdBefore) {
        Booking b = bookings.findByIdForUpdate(bookingId).orElse(null);
        if (b == null || b.getStatus() != BookingStatus.PENDING || !b.getCreatedAt().isBefore(createdBefore)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Optional<PaymentReceipt> waiting = receipts.findPendingByBookingId(bookingId);
        if (waiting.isPresent()) {
            if (waiting.get().getSubmittedAt().isAfter(now.minusMinutes(verificationHoldMinutes))) {
                return false; // an admin is still to verify the transfer
            }
        } else {
            LocalDateTime rejected = receipts.lastInitialRejectionAt(bookingId).orElse(null);
            if (rejected != null && !rejected.isBefore(createdBefore)) {
                return false; // the hold started again when the last receipt was rejected
            }
        }
        b.setStatus(BookingStatus.CANCELLED);
        b.setCancelledAt(now);
        transfers.expirePendingReceipt(b, true);
        payments.cancelOpenIntent(b);
        return true;
    }
}
