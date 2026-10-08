package com.scooterrentalkandy.spring.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lk.scooterrentkandy.models.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    List<PaymentTransaction> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    Optional<PaymentTransaction> findFirstByPaymentIdAndProviderRefOrderByCreatedAtDesc(UUID paymentId, String providerRef);

    List<PaymentTransaction> findByPaymentIdAndStatus(UUID paymentId, PaymentTransaction.Status status);
}
