package com.scooterrentalkandy.spring.services;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.GpsDtos.LivePosition;
import com.scooterrentalkandy.spring.dto.GpsDtos.Point;
import com.scooterrentalkandy.spring.dto.GpsDtos.SpeedViolationResponse;
import com.scooterrentalkandy.spring.dto.GpsDtos.Tracking;
import com.scooterrentalkandy.spring.dto.GpsDtos;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.mappers.GpsMapper;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.SpeedViolation;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.SpeedViolationRepository;
import com.scooterrentalkandy.spring.security.AuthUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GpsService {

    /** Kandy city centre (by the lake); also where the yard is. */
    public static final double BASE_LAT = 7.2936;
    public static final double BASE_LNG = 80.6413;
    /** SDS 4.3: detectSpeedViolation(speed, 60 km/h). */
    public static final BigDecimal SPEED_LIMIT_KMH = new BigDecimal("60");
    /** The largest speed the GPS log column holds (SDS 2.2: DECIMAL(5,2)). */
    static final BigDecimal MAX_SPEED_KMH = new BigDecimal("999.99");
    /** Devices report every 30 s (SDS 2.3); a position older than this is shown as stale, never moved on. */
    public static final Duration STALE_AFTER = Duration.ofMinutes(2);
    /** How far ahead of the server a device clock may be. */
    static final Duration DEVICE_CLOCK_TOLERANCE = Duration.ofMinutes(2);
    /** Minimum spacing of the rider's browser reports for one rental. */
    static final Duration MIN_RIDER_REPORT_INTERVAL = Duration.ofSeconds(5);

    private final GpsPingRepository pings;
    private final SpeedViolationRepository violations;
    private final ScooterRepository scooters;
    private final BookingRepository bookings;
    private final NotificationService notifications;
    private final DistanceService distance;
    private final Clock clock;

    public GpsService(GpsPingRepository pings, SpeedViolationRepository violations, ScooterRepository scooters,
                      BookingRepository bookings, NotificationService notifications, DistanceService distance, Clock clock) {
        this.pings = pings;
        this.violations = violations;
        this.scooters = scooters;
        this.bookings = bookings;
        this.notifications = notifications;
        this.distance = distance;
        this.clock = clock;
    }

    /**
     * One position report from a scooter's tracker. A GPS log is stored only while a rental is under way, and it
     * always belongs to that booking (SDS 2.2). Every report, idle or not, updates the scooter's last-known
     * position and speed and is checked for a speed violation.
     *
     * @param expectedBookingId optional booking the device claims; must be the rental in progress
     */
    @Transactional
    public void record(Scooter scooter, double lat, double lng, BigDecimal speed, LocalDateTime at,
                       UUID expectedBookingId) {
        LocalDateTime when = at == null ? LocalDateTime.now(clock) : at;
        BigDecimal latitude = BigDecimal.valueOf(lat).setScale(6, RoundingMode.HALF_UP);
        BigDecimal longitude = BigDecimal.valueOf(lng).setScale(6, RoundingMode.HALF_UP);
        BigDecimal kmh = speed.setScale(2, RoundingMode.HALF_UP);
        Booking rental = rentalUnderWay(scooter, when);
        if (expectedBookingId != null && (rental == null || !rental.getBookingId().equals(expectedBookingId))) {
            throw ApiException.conflict("Booking " + expectedBookingId + " is not the rental in progress on "
                    + scooter.getCode());
        }
        if (rental != null) {
            GpsPing p = new GpsPing();
            p.setScooter(scooter);
            p.setBooking(rental);
            p.setLatitude(latitude);
            p.setLongitude(longitude);
            p.setSpeedKmh(kmh);
            p.setRecordedAt(when);
            pings.save(p);
        }
        // Reports that arrive out of order (older than the last one) don't move the scooter or the speed state.
        if (scooter.getLastSeenAt() != null && when.isBefore(scooter.getLastSeenAt())) {
            return;
        }
        BigDecimal previous = scooter.getLastSpeedKmh();
        scooter.setLatitude(latitude.doubleValue());
        scooter.setLongitude(longitude.doubleValue());
        scooter.setLastSeenAt(when);
        scooter.setLastSpeedKmh(kmh);
        scooters.save(scooter);
        // Stateless crossing check: previous <= 60 and now > 60. Continuous speeding raises no further alerts.
        boolean wasWithinLimit = previous == null || previous.compareTo(SPEED_LIMIT_KMH) <= 0;
        if (wasWithinLimit && kmh.compareTo(SPEED_LIMIT_KMH) > 0) {
            speedViolation(scooter, rental, kmh, latitude, longitude, when);
        }
    }

    /**
     * A report from the scooter's tracker (SDS 4.3). The device's own time is kept, so readings it buffered while
     * offline land in the right place; a time more than {@link #DEVICE_CLOCK_TOLERANCE} ahead is refused.
     */
    @Transactional
    public void recordFromDevice(GpsDtos.PingRequest req) {
        if (req.recordedAt() != null && req.recordedAt().isAfter(LocalDateTime.now(clock).plus(DEVICE_CLOCK_TOLERANCE))) {
            throw ApiException.badRequest("recordedAt is in the future");
        }
        Scooter scooter = scooters.findByCodeIgnoreCase(req.scooterCode().trim())
                .orElseThrow(() -> ApiException.notFound("Scooter"));
        record(scooter, req.latitude(), req.longitude(), req.speedKmh(), req.recordedAt(), req.bookingId());
    }

    /**
     * A location the rider's browser/device reports during their own rental. The rider comes from the token and
     * the scooter from the booking; the time is the server's. Without a speed from the device, it is derived
     * from the previous stored report (0 for the first). Reports closer than {@link #MIN_RIDER_REPORT_INTERVAL}
     * to the previous one are ignored; device reports are not throttled, as buffered history may arrive in bursts.
     *
     * @return whether the report was recorded
     */
    @Transactional
    public boolean recordFromRider(UUID bookingId, AuthUser user, GpsDtos.LocationRequest req) {
        Booking b = bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!b.getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Booking");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        Booking underWay = rentalUnderWay(b.getScooter(), now);
        if (underWay == null || !underWay.getBookingId().equals(b.getBookingId())) {
            throw ApiException.conflict("Location can be reported only while the rental is under way");
        }
        GpsPing previous = pings.findFirstByBooking_BookingIdOrderByRecordedAtDesc(bookingId).orElse(null);
        if (previous != null && previous.getRecordedAt().isAfter(now.minus(MIN_RIDER_REPORT_INTERVAL))) {
            return false;
        }
        BigDecimal speed = req.speedKmh() != null ? req.speedKmh()
                : derivedSpeedKmh(previous, req.latitude(), req.longitude(), now);
        record(b.getScooter(), req.latitude(), req.longitude(), speed, now, b.getBookingId());
        return true;
    }

    /** Distance from the previous real report over the time since; never extrapolated, capped at the column. */
    private static BigDecimal derivedSpeedKmh(GpsPing previous, double lat, double lng, LocalDateTime now) {
        if (previous == null) {
            return BigDecimal.ZERO;
        }
        long millis = Duration.between(previous.getRecordedAt(), now).toMillis();
        if (millis <= 0) {
            return BigDecimal.ZERO;
        }
        double km = distanceKm(previous.getLatitude().doubleValue(), previous.getLongitude().doubleValue(), lat, lng);
        BigDecimal kmh = BigDecimal.valueOf(km / (millis / 3_600_000.0));
        return kmh.min(MAX_SPEED_KMH);
    }

    @Transactional(readOnly = true)
    public List<LivePosition> livePositions() {
        return scooters.findByDeletedFalseOrderByCodeAsc().stream()
                .map(this::position)
                .toList();
    }

    /** Recent speed violations for the admin dashboard (SDS 5.2). */
    @Transactional(readOnly = true)
    public List<SpeedViolationResponse> recentViolations(int limit) {
        return violations.findAllByOrderByRecordedAtDescCreatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(GpsMapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public Tracking scooterTrail(UUID scooterId, int hours) {
        Scooter s = scooters.findById(scooterId).orElseThrow(() -> ApiException.notFound("Scooter"));
        List<Point> trail = trail(scooterId, LocalDateTime.now(clock).minusHours(hours));
        return new Tracking(position(s), trail, distance.totalKm(trail));
    }

    /** Customers can see their own scooter while the rental is active, with the distance so far. */
    @Transactional(readOnly = true)
    public Tracking bookingTracking(UUID bookingId, AuthUser user) {
        Booking b = bookings.findWithDetailsByBookingId(bookingId).orElseThrow(() -> ApiException.notFound("Booking"));
        if (!user.isAdmin() && !b.getUser().getUserId().equals(user.id())) {
            throw ApiException.notFound("Booking");
        }
        if (b.getStatus() != BookingStatus.ACTIVE) {
            throw ApiException.conflict("Live tracking is available only during an active rental");
        }
        List<Point> trail = rentalTrail(b.getBookingId());
        return new Tracking(position(b.getScooter()), trail, distance.totalKm(trail));
    }

    /** computeTotalDistance: distance along the booking's GPS logs between the given times. */
    @Transactional(readOnly = true)
    public BigDecimal rentalDistanceKm(UUID bookingId, LocalDateTime from, LocalDateTime to) {
        return distance.totalKm(pings.findByBooking_BookingIdAndRecordedAtBetweenOrderByRecordedAtAsc(bookingId, from, to)
                .stream().map(GpsMapper::toPoint).toList());
    }

    /**
     *  dropLocation: the rental's last GPS position, else the scooter's last-known position. Formatted as
     * "lat, lng"; this stands in for Google reverse geocoding, which would return a street address.
     */
    @Transactional(readOnly = true)
    public String dropLocation(UUID bookingId, Scooter scooter) {
        return pings.findFirstByBooking_BookingIdOrderByRecordedAtDesc(bookingId)
                .map(p -> coordinates(p.getLatitude().doubleValue(), p.getLongitude().doubleValue()))
                .orElseGet(() -> scooter.getLatitude() == null || scooter.getLongitude() == null ? "Not recorded"
                        : coordinates(scooter.getLatitude(), scooter.getLongitude()));
    }

    private static String coordinates(double lat, double lng) {
        return String.format(java.util.Locale.ROOT, "%.6f, %.6f", lat, lng);
    }

    @Transactional(readOnly = true)
    public boolean hasRentalLogs(UUID bookingId) {
        return pings.existsByBooking_BookingId(bookingId);
    }

    @Transactional
    public int purgeOlderThan(LocalDateTime cutoff) {
        return pings.deleteOlderThanOutsideActiveRentals(cutoff);
    }

    /** Pings belong to a booking only while its rental is under way: started and not yet ended. */
    private Booking rentalUnderWay(Scooter scooter, LocalDateTime at) {
        return bookings.findFirstByScooter_ScooterIdAndStatus(scooter.getScooterId(), BookingStatus.ACTIVE)
                .filter(b -> !at.isBefore(b.getStartTime()) && b.getEndedAt() == null)
                .orElse(null);
    }

    private void speedViolation(Scooter s, Booking rental, BigDecimal kmh, BigDecimal lat, BigDecimal lng,
                                LocalDateTime at) {
        SpeedViolation v = new SpeedViolation();
        v.setScooter(s);
        v.setBooking(rental);
        v.setSpeedKmh(kmh);
        v.setLatitude(lat);
        v.setLongitude(lng);
        v.setRecordedAt(at);
        v.setCreatedAt(LocalDateTime.now(clock));
        violations.save(v);
        // In-app only: SMS alerts (SDS: SNS) wait for a real SMS provider rather than a pretend delivery.
        notifications.notifyAdmins("Speed violation: " + s.getCode(),
                s.getModel() + " " + s.getPlateNumber() + " recorded " + kmh.setScale(0, RoundingMode.HALF_UP)
                        + " km/h (limit " + SPEED_LIMIT_KMH + " km/h)"
                        + (rental == null ? " while not on a rental." : " on rental " + rental.getReference() + "."));
    }

    private List<Point> rentalTrail(UUID bookingId) {
        return pings.findByBooking_BookingIdOrderByRecordedAtAsc(bookingId).stream().map(GpsMapper::toPoint).toList();
    }

    private List<Point> trail(UUID scooterId, LocalDateTime since) {
        return pings.findByScooter_ScooterIdAndRecordedAtAfterOrderByRecordedAtAsc(scooterId, since).stream()
                .map(GpsMapper::toPoint).toList();
    }

    private LivePosition position(Scooter s) {
        Booking active = bookings.findFirstByScooter_ScooterIdAndStatus(s.getScooterId(), BookingStatus.ACTIVE).orElse(null);
        double dist = s.getLatitude() == null ? 0
                : distanceKm(BASE_LAT, BASE_LNG, s.getLatitude(), s.getLongitude());
        boolean stale = s.getLastSeenAt() == null
                || s.getLastSeenAt().isBefore(LocalDateTime.now(clock).minus(STALE_AFTER));
        return GpsMapper.toLivePosition(s, active, Math.round(dist * 10) / 10.0, stale);
    }

    /** Haversine great-circle distance. */
    public static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double r = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * r * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
