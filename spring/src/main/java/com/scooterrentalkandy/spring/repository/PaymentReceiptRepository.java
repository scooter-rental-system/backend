package com.scooterrentalkandy.spring.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentReceiptRepository extends JpaRepository<PaymentReceipt, UUID> {

    List<PaymentReceipt> findByPayment_PaymentIdOrderBySubmittedAtAsc(UUID paymentId);

    /** The receipt waiting for verification on this booking's payment (at most one, by a unique index). */
    @Query("select r from PaymentReceipt r where r.payment.booking.bookingId = :bookingId"
            + " and r.status = com.scooterrentalkandy.spring.entity.PaymentReceipt.Status.PENDING_VERIFICATION")
    Optional<PaymentReceipt> findPendingByBookingId(@Param("bookingId") UUID bookingId);

    /** When a receipt for the booking's initial payment was last rejected, if one was. */
    @Query("select max(r.reviewedAt) from PaymentReceipt r where r.payment.booking.bookingId = :bookingId"
            + " and r.kind = com.scooterrentalkandy.spring.entity.PaymentReceipt.Kind.INITIAL"
            + " and r.status = com.scooterrentalkandy.spring.entity.PaymentReceipt.Status.REJECTED")
    Optional<LocalDateTime> lastInitialRejectionAt(@Param("bookingId") UUID bookingId);

    /** The booking a receipt belongs to (scalar query, so no row is read before the booking is locked). */
    @Query("select r.payment.booking.bookingId from PaymentReceipt r where r.paymentReceiptId = :receiptId")
    Optional<UUID> findBookingIdByReceiptId(@Param("receiptId") UUID receiptId);

    @EntityGraph(attributePaths = {"payment", "payment.booking", "payment.booking.user", "reviewedBy"})
    List<PaymentReceipt> findTop300ByOrderBySubmittedAtDesc();
}
