package com.scooterrentalkandy.spring.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    /** Serialises payment, completion and cancellation of the same booking. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from Booking b where b.bookingId = :id")
    Optional<Booking> findByIdForUpdate(@Param("id") UUID id);

    /** SDS 4.2 step 4: a scooter is taken while it has a pending or active booking. */
    boolean existsByScooter_ScooterIdAndStatusIn(UUID scooterId, Collection<BookingStatus> statuses);

    @Query("select distinct b.scooter.scooterId from Booking b where b.status in :statuses")
    List<UUID> findScooterIdsWithStatus(@Param("statuses") Collection<BookingStatus> statuses);

    @EntityGraph(attributePaths = {"scooter", "user"})
    List<Booking> findByUser_UserIdOrderByCreatedAtDesc(UUID userId);

    @EntityGraph(attributePaths = {"scooter", "user"})
    List<Booking> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"scooter", "user"})
    List<Booking> findByStatusOrderByStartTimeAsc(BookingStatus status);

    @EntityGraph(attributePaths = {"scooter", "user", "gear", "gear.gearItem"})
    Optional<Booking> findWithDetailsByBookingId(UUID bookingId);

    Optional<Booking> findFirstByScooter_ScooterIdAndStatus(UUID scooterId, BookingStatus status);

    /** Rentals under way: started and not yet ended. */
    @EntityGraph(attributePaths = {"scooter"})
    List<Booking> findByStatusAndStartTimeLessThanEqualAndEndedAtIsNull(BookingStatus status, LocalDateTime now);

    /** IDs only: each candidate is then locked and re-checked in its own transaction. */
    @Query("select b.bookingId from Booking b where b.status = :status and b.createdAt < :cutoff")
    List<UUID> findIdsByStatusAndCreatedAtBefore(@Param("status") BookingStatus status,
                                                 @Param("cutoff") LocalDateTime cutoff);

    @EntityGraph(attributePaths = {"scooter", "user"})
    List<Booking> findByStatusAndStartTimeBetween(BookingStatus status, LocalDateTime from, LocalDateTime to);

    /** Rentals whose time overlaps the window (open rentals run until now). */
    @EntityGraph(attributePaths = {"scooter"})
    @Query("""
            select b from Booking b
            where b.status in :statuses and b.startTime < :to and (b.endTime is null or b.endTime > :from)
            """)
    List<Booking> findRentalsOverlapping(@Param("statuses") Collection<BookingStatus> statuses,
                                         @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @EntityGraph(attributePaths = {"scooter", "user"})
    List<Booking> findByCreatedAtBetween(LocalDateTime from, LocalDateTime to);

    long countByStatus(BookingStatus status);
}
