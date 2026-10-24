package com.scooterrentalkandy.spring.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByPayment_PaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<PaymentTransaction> findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(UUID paymentId, String providerRef);

    List<PaymentTransaction> findByPayment_PaymentIdAndStatus(UUID paymentId, PaymentTransaction.Status status);

    /** Attempt counter for idempotency keys: provider calls of this kind already recorded for the payment. */
    long countByPayment_PaymentIdAndKind(UUID paymentId, PaymentTransaction.Kind kind);
}
