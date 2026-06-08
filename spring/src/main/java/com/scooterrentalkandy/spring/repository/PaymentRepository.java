package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.common.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    /** Find the payment associated with a specific booking (1-to-1). */
    Optional<Payment> findByBooking_BookingId(UUID bookingId);

    /** Look up a payment by its Stripe PaymentIntent ID (used by webhook handler). */
    Optional<Payment> findByStripePaymentId(String stripePaymentId);

    /** All payments in a given state (e.g., for reconciliation). */
    List<Payment> findByStatus(PaymentStatus status);
}
