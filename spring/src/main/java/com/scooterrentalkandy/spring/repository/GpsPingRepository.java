package com.scooterrentalkandy.spring.repository;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.GpsPing;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GpsPingRepository extends JpaRepository<GpsPing, UUID> {

    List<GpsPing> findByScooter_ScooterIdAndRecordedAtAfterOrderByRecordedAtAsc(UUID scooterId, LocalDateTime after);

    Optional<GpsPing> findFirstByScooter_ScooterIdOrderByRecordedAtDesc(UUID scooterId);

    List<GpsPing> findByBooking_BookingIdOrderByRecordedAtAsc(UUID bookingId);

    List<GpsPing> findByBooking_BookingIdAndRecordedAtBetweenOrderByRecordedAtAsc(UUID bookingId, LocalDateTime from,
                                                                          LocalDateTime to);

    Optional<GpsPing> findFirstByBooking_BookingIdOrderByRecordedAtDesc(UUID bookingId);

    /** The booking's latest reports, newest first (for the sustained over-limit check on rider reports). */
    List<GpsPing> findTop4ByBooking_BookingIdOrderByRecordedAtDesc(UUID bookingId);

    boolean existsByBooking_BookingId(UUID bookingId);

    /**
     *  deletes logs recorded before the cutoff, except those of a rental still under way
     * (its distance is not billed yet). Logs of completed or cancelled bookings go only once they pass the cutoff.
     */
    @Modifying
    @Query("""
            delete from GpsPing p where p.recordedAt < :cutoff
              and p.booking.bookingId not in (
                  select b.bookingId from Booking b where b.status = com.scooterrentalkandy.spring.entity.enums.BookingStatus.ACTIVE)
            """)
    int deleteOlderThanOutsideActiveRentals(@Param("cutoff") LocalDateTime cutoff);
}
