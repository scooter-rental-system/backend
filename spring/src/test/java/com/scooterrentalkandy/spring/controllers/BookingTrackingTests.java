package com.scooterrentalkandy.spring.controllers;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/** GET /api/bookings/{id}/tracking: the rider's view of their active rental. Each test rolls back. */
@AutoConfigureMockMvc
@Transactional
class BookingTrackingTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private GpsPingRepository pings;

    @Autowired
    private JwtService jwt;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("track." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Tracked Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("K" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Booking booking(User rider, BookingStatus status) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("TK-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("TKP-" + id);
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setLatitude(7.31);
        s.setLongitude(80.63);
        s.setLastSeenAt(now);
        Booking b = new Booking();
        b.setReference("BK-TK" + id);
        b.setUser(rider);
        b.setScooter(scooters.saveAndFlush(s));
        b.setStatus(status);
        b.setStartTime(now.minusHours(1));
        b.setPickupLocation("Test pickup");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(now.minusMinutes(10));
            b.setDistanceKm(new BigDecimal("1.00"));
            b.setTotalCost(new BigDecimal("265.00"));
        }
        return bookings.saveAndFlush(b);
    }

    private void ping(Booking b, String lat, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(b.getScooter());
        p.setBooking(b);
        p.setLatitude(new BigDecimal(lat));
        p.setLongitude(new BigDecimal("80.630000"));
        p.setSpeedKmh(new BigDecimal("20.00"));
        p.setRecordedAt(at);
        pings.saveAndFlush(p);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    @Test
    void theRiderFollowsTheirActiveRental() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, BookingStatus.ACTIVE);
        ping(rental, "7.290000", now.minusMinutes(20));
        ping(rental, "7.300000", now.minusMinutes(10));
        ping(rental, "7.310000", now);

        mvc.perform(as(rider, get("/api/bookings/" + rental.getBookingId() + "/tracking")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position.scooterId").value(rental.getScooter().getScooterId().toString()))
                .andExpect(jsonPath("$.position.latitude").value(7.31))
                .andExpect(jsonPath("$.position.stale").value(false))
                .andExpect(jsonPath("$.trail", hasSize(3)))
                .andExpect(jsonPath("$.trail[0].latitude").value(7.29))
                .andExpect(jsonPath("$.trail[2].speedKmh").value(20.0))
                .andExpect(jsonPath("$.distanceKm").value(2.22));
    }

    @Test
    void anotherCustomerCannotFollowTheRental() throws Exception {
        Booking rental = booking(saveUser(Role.USER), BookingStatus.ACTIVE);
        ping(rental, "7.290000", now);

        mvc.perform(as(saveUser(Role.USER), get("/api/bookings/" + rental.getBookingId() + "/tracking")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.trail").doesNotExist());
        mvc.perform(get("/api/bookings/" + rental.getBookingId() + "/tracking")).andExpect(status().isUnauthorized());
    }

    @Test
    void adminsCanFollowAnyActiveRental() throws Exception {
        Booking rental = booking(saveUser(Role.USER), BookingStatus.ACTIVE);

        mvc.perform(as(saveUser(Role.ADMIN), get("/api/bookings/" + rental.getBookingId() + "/tracking")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trail", hasSize(0)));
    }

    @Test
    void trackingIsOnlyForActiveRentals() throws Exception {
        User rider = saveUser(Role.USER);
        for (BookingStatus status : new BookingStatus[] {BookingStatus.PENDING, BookingStatus.COMPLETED}) {
            Booking b = booking(rider, status);

            mvc.perform(as(rider, get("/api/bookings/" + b.getBookingId() + "/tracking")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Live tracking is available only during an active rental"));
        }
    }
}
