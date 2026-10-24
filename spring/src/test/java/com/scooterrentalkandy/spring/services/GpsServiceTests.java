package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.SpeedViolationRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;

/**
 * Characterizes the GPS service rules against the embedded PostgreSQL. The coordinates are test fixtures fed to
 * the service exactly as a real report would be; nothing here generates positions. Each test rolls back.
 */
@Transactional
class GpsServiceTests extends PostgresIntegrationTest {

    /** Fixture coordinates in Kandy. */
    private static final double LAT = 7.290000;
    private static final double LNG = 80.630000;

    @Autowired
    private GpsService gps;

    @Autowired
    private GpsPingRepository pings;

    @Autowired
    private SpeedViolationRepository violations;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private UserRepository users;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("GP-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("GPP-" + id);
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private User saveCustomer() {
        String id = unique();
        User u = new User();
        u.setEmail("gps." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Gps Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("T" + id);
        u.setRole(Role.USER);
        return users.saveAndFlush(u);
    }

    /** Fixture: a booking in the given state on the scooter, starting at {@code start}. */
    private Booking booking(Scooter scooter, BookingStatus status, LocalDateTime start) {
        Booking b = new Booking();
        b.setReference("BK-GPS" + unique());
        b.setUser(saveCustomer());
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(start);
        b.setPickupLocation("Test pickup");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(start.plusHours(1));
            b.setDistanceKm(new BigDecimal("1.00"));
            b.setTotalCost(new BigDecimal("265.00"));
        }
        if (status == BookingStatus.CANCELLED) {
            b.setCancelledAt(start.minusMinutes(5));
        }
        return bookings.saveAndFlush(b);
    }

    private Booking rentalUnderWay(Scooter scooter) {
        return booking(scooter, BookingStatus.ACTIVE, now.minusHours(1));
    }

    private void report(Scooter scooter, double lat, double lng, String kmh, LocalDateTime at) {
        gps.record(scooter, lat, lng, new BigDecimal(kmh), at, null);
    }

    private Scooter reload(Scooter s) {
        return scooters.findById(s.getScooterId()).orElseThrow();
    }

    // ── What is stored ────────────────────────────────────────────────────────

    @Test
    void aReportDuringARentalIsStoredWithItsBooking() {
        Scooter scooter = saveScooter();
        Booking rental = rentalUnderWay(scooter);

        report(scooter, LAT, LNG, "18.5", now);

        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(rental.getBookingId())).singleElement()
                .satisfies(p -> {
                    assertThat(p.getLatitude()).isEqualByComparingTo("7.290000");
                    assertThat(p.getLongitude()).isEqualByComparingTo("80.630000");
                    assertThat(p.getSpeedKmh()).isEqualByComparingTo("18.50");
                    assertThat(p.getRecordedAt()).isEqualTo(now);
                });
        Scooter live = reload(scooter);
        assertThat(live.getLatitude()).isEqualTo(LAT);
        assertThat(live.getLongitude()).isEqualTo(LNG);
        assertThat(live.getLastSeenAt()).isEqualTo(now);
        assertThat(live.getLastSpeedKmh()).isEqualByComparingTo("18.50");
        assertThat(gps.hasRentalLogs(rental.getBookingId())).isTrue();
    }

    @Test
    void reportsOutsideARentalUnderWayAreNotStoredButUpdateTheLastKnownPosition() {
        Scooter idle = saveScooter();
        Scooter pending = saveScooter();
        booking(pending, BookingStatus.PENDING, now.minusHours(1));
        Scooter notStarted = saveScooter();
        booking(notStarted, BookingStatus.ACTIVE, now.plusHours(2));
        Scooter completed = saveScooter();
        booking(completed, BookingStatus.COMPLETED, now.minusHours(3));
        Scooter cancelled = saveScooter();
        booking(cancelled, BookingStatus.CANCELLED, now.minusHours(1));
        Scooter ended = saveScooter();
        Booking balanceDue = booking(ended, BookingStatus.ACTIVE, now.minusHours(2));
        balanceDue.setEndedAt(now.minusMinutes(30));
        balanceDue.setPendingTotalCost(new BigDecimal("500.00"));
        balanceDue.setPendingDistanceKm(new BigDecimal("3.00"));
        bookings.saveAndFlush(balanceDue);

        for (Scooter s : new Scooter[] {idle, pending, notStarted, completed, cancelled, ended}) {
            report(s, LAT, LNG, "0", now);

            assertThat(pings.findFirstByScooter_ScooterIdOrderByRecordedAtDesc(s.getScooterId()))
                    .as(s.getCode()).isEmpty();
            assertThat(reload(s).getLastSeenAt()).as(s.getCode()).isEqualTo(now);
        }
    }

    @Test
    void aDeviceNamingAnotherBookingIsRefused() {
        Scooter scooter = saveScooter();
        rentalUnderWay(scooter);

        assertThatThrownBy(() -> gps.record(scooter, LAT, LNG, BigDecimal.TEN, now, UUID.randomUUID()))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(pings.findFirstByScooter_ScooterIdOrderByRecordedAtDesc(scooter.getScooterId())).isEmpty();
    }

    @Test
    void anOlderReportIsStoredButDoesNotMoveTheScooterBack() {
        Scooter scooter = saveScooter();
        Booking rental = rentalUnderWay(scooter);

        report(scooter, 7.300000, 80.640000, "20", now);
        report(scooter, 7.295000, 80.635000, "20", now.minusMinutes(1));

        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(rental.getBookingId())).hasSize(2);
        Scooter live = reload(scooter);
        assertThat(live.getLatitude()).isEqualTo(7.3);
        assertThat(live.getLastSeenAt()).isEqualTo(now);
    }

    // ── Distance and drop location ────────────────────────────────────────────

    @Test
    void rentalDistanceSumsTheRecordedHops() {
        Scooter scooter = saveScooter();
        Booking rental = rentalUnderWay(scooter);
        report(scooter, 7.290000, LNG, "20", now.minusMinutes(20));
        report(scooter, 7.300000, LNG, "20", now.minusMinutes(10));
        report(scooter, 7.310000, LNG, "20", now);

        // Two hops of 0.01 degrees of latitude: about 1.11 km each.
        assertThat(gps.rentalDistanceKm(rental.getBookingId(), now.minusHours(1), now)).isEqualByComparingTo("2.22");
        assertThat(gps.rentalDistanceKm(rental.getBookingId(), now.minusMinutes(15), now))
                .isEqualByComparingTo("1.11");
    }

    @Test
    void dropLocationIsTheLastRentalReportThenTheLastKnownPosition() {
        Scooter scooter = saveScooter();
        Booking rental = rentalUnderWay(scooter);
        report(scooter, 7.291234, 80.631234, "10", now.minusMinutes(5));
        report(scooter, 7.295678, 80.635678, "10", now);
        assertThat(gps.dropLocation(rental.getBookingId(), scooter)).isEqualTo("7.295678, 80.635678");

        Scooter idle = saveScooter();
        Booking noLogs = booking(idle, BookingStatus.COMPLETED, now.minusHours(3));
        assertThat(gps.dropLocation(noLogs.getBookingId(), idle)).isEqualTo("Not recorded");
        report(idle, 7.280000, 80.620000, "0", now);
        assertThat(gps.dropLocation(noLogs.getBookingId(), reload(idle))).isEqualTo("7.280000, 80.620000");
        assertThat(gps.hasRentalLogs(noLogs.getBookingId())).isFalse();
    }

    // ── Speed violations ──────────────────────────────────────────────────────

    @Test
    void aViolationIsRecordedEachTimeTheSpeedCrossesSixty() {
        Scooter scooter = saveScooter();
        Booking rental = rentalUnderWay(scooter);

        report(scooter, LAT, LNG, "60", now.minusMinutes(5));
        report(scooter, LAT, LNG, "61", now.minusMinutes(4));
        report(scooter, LAT, LNG, "75", now.minusMinutes(3));
        report(scooter, LAT, LNG, "40", now.minusMinutes(2));
        report(scooter, LAT, LNG, "65", now.minusMinutes(1));

        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(scooter.getScooterId()))
                .extracting(v -> v.getSpeedKmh().toPlainString(), v -> v.getBooking().getBookingId())
                .containsExactly(
                        tuple("61.00", rental.getBookingId()),
                        tuple("65.00", rental.getBookingId()));
    }

    // ── Retention purge ───────────────────────────────────────────────────────

    private GpsPing storedPing(Scooter scooter, Booking booking, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(scooter);
        p.setBooking(booking);
        p.setLatitude(new BigDecimal("7.290000"));
        p.setLongitude(new BigDecimal("80.630000"));
        p.setSpeedKmh(new BigDecimal("10.00"));
        p.setRecordedAt(at);
        return pings.saveAndFlush(p);
    }

    @Test
    void purgeRemovesOnlyLogsBeforeTheCutoffOutsideRentalsUnderWay() {
        LocalDateTime cutoff = now.minusMonths(6);
        Scooter finishedScooter = saveScooter();
        Booking finished = booking(finishedScooter, BookingStatus.COMPLETED, cutoff.minusDays(2));
        GpsPing old = storedPing(finishedScooter, finished, cutoff.minusSeconds(1));
        GpsPing atCutoff = storedPing(finishedScooter, finished, cutoff);
        GpsPing recent = storedPing(finishedScooter, finished, now.minusDays(1));
        Scooter ridingScooter = saveScooter();
        Booking underWay = booking(ridingScooter, BookingStatus.ACTIVE, cutoff.minusDays(3));
        GpsPing oldButRiding = storedPing(ridingScooter, underWay, cutoff.minusDays(1));

        int purged = gps.purgeOlderThan(cutoff);

        assertThat(purged).isGreaterThanOrEqualTo(1);
        assertThat(pings.existsById(old.getGpsPingId())).isFalse();
        assertThat(pings.existsById(atCutoff.getGpsPingId())).isTrue();
        assertThat(pings.existsById(recent.getGpsPingId())).isTrue();
        assertThat(pings.existsById(oldButRiding.getGpsPingId())).isTrue();
    }
}
