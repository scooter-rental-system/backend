package com.scooterrentalkandy.spring.repository;


import com.scooterrentalkandy.spring.entity.GpsLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Repository
public interface GpsLogRepository extends JpaRepository<GpsLog, UUID> {

    /**
     * All GPS logs for a booking ordered by time — used by GPSBillingService
     * to compute the total distance via Google Maps Distance Matrix API.
     */
    List<GpsLog> findByBooking_BookingIdOrderByRecordedAtAsc(UUID bookingId);

    /**
     * Count speed-violation events for a booking (speed > threshold km/h).
     * Threshold is typically 60 km/h per the SDS spec.
     */
    @Query("""
           SELECT COUNT(g) FROM GpsLog g
           WHERE g.booking.bookingId = :bookingId
             AND g.speed > :speedLimit
           """)
    long countSpeedViolations(@Param("bookingId")  UUID bookingId,
                              @Param("speedLimit") BigDecimal speedLimit);

    /**
     * Delete all GPS logs older than 6 months — called by the scheduled
     * purge job as defined in the data-retention policy (Chapter 8).
     */
    @Query("DELETE FROM GpsLog g WHERE g.recordedAt < :cutoff")
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.transaction.annotation.Transactional
    void deleteLogsOlderThan(
            @Param("cutoff") java.time.Instant cutoff);
}
