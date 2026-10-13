package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBooking_BookingId(UUID bookingId);

    /** Serialises webhook deliveries and completion for the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.booking.bookingId = :bookingId")
    Optional<Payment> findByBookingIdForUpdate(@Param("bookingId") UUID bookingId);

    @EntityGraph(attributePaths = {"booking", "booking.customer"})
    List<Payment> findTop300ByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"booking", "booking.scooter"})
    List<Payment> findByStatusInAndPaidAtBetween(Collection<PaymentStatus> statuses, LocalDateTime from,
                                                 LocalDateTime to);

    List<Payment> findByBalanceDueIsNotNull();

    @EntityGraph(attributePaths = {"booking", "booking.scooter"})
    List<Payment> findByStatusAndBookingCancelledAtBetween(PaymentStatus status, LocalDateTime from,
                                                           LocalDateTime to);
}
