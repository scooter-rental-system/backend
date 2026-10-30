package com.scooterrentalkandy.spring.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByPayment_PaymentIdOrderByCreatedAtAsc(UUID paymentId);

    /** The transactions of several payments at once, oldest first (for a payment list). */
    List<PaymentTransaction> findByPayment_PaymentIdInOrderByCreatedAtAsc(Collection<UUID> paymentIds);

    Optional<PaymentTransaction> findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(UUID paymentId, String providerRef);

    List<PaymentTransaction> findByPayment_PaymentIdAndStatus(UUID paymentId, PaymentTransaction.Status status);

    /** Attempt counter for idempotency keys: provider calls of this kind already recorded for the payment. */
    long countByPayment_PaymentIdAndKind(UUID paymentId, PaymentTransaction.Kind kind);

    /** Refunds made against one intent of the payment. */
    List<PaymentTransaction> findByPayment_PaymentIdAndKindAndIntentRef(UUID paymentId, PaymentTransaction.Kind kind,
                                                                        String intentRef);

    /** The booking an intent was created for (scalar query, so no row is read before the booking is locked). */
    @Query("select t.payment.booking.bookingId from PaymentTransaction t where t.providerRef = :providerRef")
    List<UUID> findBookingIdsByProviderRef(@Param("providerRef") String providerRef);
}
