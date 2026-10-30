package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

/**
 * The GPS retention job against the embedded PostgreSQL. The job is built with a fixed clock so its cutoff is
 * exact; it is not scheduled in tests (scheduling is off in the test profile). Each test rolls back.
 */
@Transactional
class GpsRetentionJobTests extends PostgresIntegrationTest {

    @Autowired
    private GpsService gps;

    @Autowired
    private GpsPingRepository pings;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private UserRepository users;

    @Autowired
    private AppProperties props;

    private final Clock clock = Clock.fixed(Instant.now().truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault());
    private final LocalDateTime now = LocalDateTime.now(clock);

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private Booking booking(BookingStatus status, LocalDateTime start) {
        String id = unique();
        User u = new User();
        u.setEmail("retain." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Retention " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("N" + id);
        u.setRole(Role.USER);
        Scooter s = new Scooter();
        s.setCode("RT-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("RTP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        Booking b = new Booking();
        b.setReference("BK-RT" + id);
        b.setUser(users.saveAndFlush(u));
        b.setScooter(scooters.saveAndFlush(s));
        b.setStatus(status);
        b.setStartTime(start);
        b.setPickupLocation("Test pickup");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(start.plusHours(2));
            b.setDistanceKm(new BigDecimal("3.00"));
            b.setTotalCost(new BigDecimal("545.00"));
        }
        return bookings.saveAndFlush(b);
    }

    private GpsPing ping(Booking b, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(b.getScooter());
        p.setBooking(b);
        p.setLatitude(new BigDecimal("7.290000"));
        p.setLongitude(new BigDecimal("80.630000"));
        p.setSpeedKmh(new BigDecimal("15.00"));
        p.setRecordedAt(at);
        return pings.saveAndFlush(p);
    }

    @Test
    void logsOlderThanSixMonthsArePurgedExceptARentalStillUnderWay() {
        GpsRetentionJob job = new GpsRetentionJob(gps, clock, props);
        LocalDateTime cutoff = now.minusMonths(6);
        Booking finished = booking(BookingStatus.COMPLETED, cutoff.minusDays(1));
        GpsPing beforeCutoff = ping(finished, cutoff.minusSeconds(1));
        GpsPing atCutoff = ping(finished, cutoff);
        GpsPing recent = ping(finished, now.minusDays(30));
        Booking stillRiding = booking(BookingStatus.ACTIVE, cutoff.minusDays(2));
        GpsPing oldButRiding = ping(stillRiding, cutoff.minusDays(1));

        job.purge();

        assertThat(job.cutoff()).isEqualTo(cutoff);
        assertThat(pings.existsById(beforeCutoff.getGpsPingId())).isFalse();
        assertThat(pings.existsById(atCutoff.getGpsPingId())).isTrue();
        assertThat(pings.existsById(recent.getGpsPingId())).isTrue();
        assertThat(pings.existsById(oldButRiding.getGpsPingId())).isTrue();
    }

    @Test
    void retentionIsSixMonthsAndDefaultsToSixWhenUnset() {
        Clock fixed = Clock.fixed(Instant.parse("2026-10-24T21:45:30.123Z"), ZoneId.of("Asia/Colombo"));
        AppProperties unset = new AppProperties(null, null, null, null, null, new AppProperties.Gps(null, 0), null);

        assertThat(props.gps().retentionMonths()).isEqualTo(6);
        assertThat(new GpsRetentionJob(gps, fixed, props).cutoff()).isEqualTo(LocalDateTime.of(2026, 4, 25, 3, 15, 30));
        assertThat(new GpsRetentionJob(gps, fixed, unset).cutoff()).isEqualTo(LocalDateTime.of(2026, 4, 25, 3, 15, 30));
    }

    @Test
    void runsDailyAt0315ColomboTime() throws Exception {
        Scheduled schedule = GpsRetentionJob.class.getMethod("purge").getAnnotation(Scheduled.class);

        assertThat(schedule.cron()).isEqualTo("0 15 3 * * *");
        assertThat(schedule.zone()).isEqualTo("Asia/Colombo");
    }
}
