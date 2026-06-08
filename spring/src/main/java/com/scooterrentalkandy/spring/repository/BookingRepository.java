package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.common.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.Booking;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** All bookings placed by a specific user (booking history). */
    List<Booking> findByUser_UserId(UUID userId);

    /** All bookings associated with a specific scooter. */
    List<Booking> findByScooter_ScooterId(UUID scooterId);

    /** All bookings in a given lifecycle state. */
    List<Booking> findByStatus(BookingStatus status);

    /**
     * Check whether there is already an ACTIVE booking for a scooter —
     * used to enforce the single-active-booking-per-scooter business rule.
     */
    boolean existsByScooter_ScooterIdAndStatus(UUID scooterId, BookingStatus status);

    /**
     * Find the single active booking for a scooter (used by GPS billing flow).
     */
    Optional<Booking> findByScooter_ScooterIdAndStatus(UUID scooterId, BookingStatus status);

    /**
     * All bookings for a user filtered by status (e.g., show only COMPLETED).
     */
    List<Booking> findByUser_UserIdAndStatus(UUID userId, BookingStatus status);

    /**
     * Admin report query: bookings whose startTime falls within a date range.
     */
    @Query("SELECT b FROM Booking b WHERE b.startTime >= :from AND b.startTime <= :to")
    List<Booking> findByStartTimeBetween(@Param("from") Instant from,
                                         @Param("to")   Instant to);

    /**
     * Admin report query: bookings by status within a date range.
     */
    @Query("""
           SELECT b FROM Booking b
           WHERE b.status = :status
             AND b.startTime >= :from
             AND b.startTime <= :to
           """)
    List<Booking> findByStatusAndStartTimeBetween(@Param("status") BookingStatus status,
                                                  @Param("from")   Instant from,
                                                  @Param("to")     Instant to);
}