package com.scooterrentalkandy.spring.services;

import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stands in for real GPS trackers during local development: scooters on a rental that has started wander
 * around Kandy, scooters in the yard (or reserved for a rental that starts later) stay parked. Disable with GPS_SIMULATOR=false
 * once real devices post to /api/gps/pings.
 */
@Component
@ConditionalOnProperty(name = "app.gps.simulator-enabled", havingValue = "true")
public class MockGpsSimulator {

    /** Keeps simulated rides roughly within the Kandy district. */
    private static final double MAX_RADIUS_KM = 25.0;

    private final BookingRepository bookings;
    private final GpsService gps;

    public MockGpsSimulator(BookingRepository bookings, GpsService gps) {
        this.bookings = bookings;
        this.gps = gps;
    }

    @Scheduled(fixedDelayString = "${app.gps.simulator-interval-ms:30000}", initialDelay = 5_000)
    @Transactional
    public void tick() {
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        LocalDateTime now = LocalDateTime.now();
        for (Booking rental : bookings.findByStatusAndStartTimeLessThanEqualAndEndedAtIsNull(BookingStatus.ACTIVE, now)) {
            Scooter s = rental.getScooter();
            double lat = s.getLatitude() == null ? GpsService.BASE_LAT : s.getLatitude();
            double lng = s.getLongitude() == null ? GpsService.BASE_LNG : s.getLongitude();
            // ~up to 300 m per 30 s tick in a random direction.
            double nextLat = lat + (rnd.nextDouble() - 0.5) * 0.0054;
            double nextLng = lng + (rnd.nextDouble() - 0.5) * 0.0054;
            if (GpsService.distanceKm(GpsService.BASE_LAT, GpsService.BASE_LNG, nextLat, nextLng) > MAX_RADIUS_KM) {
                // Drift back toward town.
                nextLat = lat + (GpsService.BASE_LAT - lat) * 0.1;
                nextLng = lng + (GpsService.BASE_LNG - lng) * 0.1;
            }
            double speed = Math.round(rnd.nextDouble(0, 45) * 10) / 10.0;
            gps.record(s, nextLat, nextLng, speed, now);
        }
    }
}
