package com.scooterrentalkandy.spring.controllers;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin GPS history of one rental: only that booking's stored reports, in time order, for admins only. Another rental
 * on the same scooter is never mixed in. Reports are stored directly (test fixtures, not simulated tracking).
 */
@AutoConfigureMockMvc
@Transactional
class GpsHistoryTests extends PostgresIntegrationTest {

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

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("history." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("History Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("history" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("GH-" + id);
        s.setModel("History Dio");
        s.setPlateNumber("GHP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setStatus(ScooterStatus.RENTED);
        return scooters.saveAndFlush(s);
    }

    private Booking booking(User customer, Scooter s, BookingStatus status, LocalDateTime start) {
        Booking b = new Booking();
        b.setReference("BK-GH" + unique());
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(status);
        b.setStartTime(start);
        b.setContractSigned(true);
        b.setPickupLocation("Kandy office");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(start.plusHours(2));
            b.setDistanceKm(new BigDecimal("1.00"));
            b.setTotalCost(new BigDecimal("500.00"));
        }
        return bookings.saveAndFlush(b);
    }

    private void ping(Booking b, double lat, double lng, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(b.getScooter());
        p.setBooking(b);
        p.setLatitude(BigDecimal.valueOf(lat));
        p.setLongitude(BigDecimal.valueOf(lng));
        p.setSpeedKmh(new BigDecimal("20.00"));
        p.setRecordedAt(at);
        pings.saveAndFlush(p);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    @Test
    void anAdminSeesOneRentalsRouteInTimeOrderAndNothingElse() throws Exception {
        User rider = saveUser(Role.USER);
        Scooter s = saveScooter();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        Booking earlier = booking(saveUser(Role.USER), s, BookingStatus.COMPLETED, now.minusDays(2));
        Booking current = booking(rider, s, BookingStatus.ACTIVE, now.minusHours(1));
        ping(earlier, 7.2000, 80.6000, now.minusDays(2).plusMinutes(10));
        // Stored out of order on purpose: the history is sorted by recorded time.
        ping(current, 7.2950, 80.6350, now.minusMinutes(10));
        ping(current, 7.2936, 80.6413, now.minusMinutes(50));
        ping(current, 7.2940, 80.6400, now.minusMinutes(30));

        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/gps/bookings/" + current.getBookingId() + "/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reference").value(current.getReference()))
                .andExpect(jsonPath("$.customerName").value(rider.getFullName()))
                .andExpect(jsonPath("$.scooterCode").value(s.getCode()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.end").doesNotExist())
                .andExpect(jsonPath("$.points.length()").value(3))
                .andExpect(jsonPath("$.points[0].latitude").value(7.2936))
                .andExpect(jsonPath("$.points[1].latitude").value(7.2940))
                .andExpect(jsonPath("$.points[2].latitude").value(7.2950))
                .andExpect(jsonPath("$.distanceKm").isNumber());

        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/gps/bookings/" + earlier.getBookingId() + "/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1))
                .andExpect(jsonPath("$.end").isNotEmpty());
    }

    @Test
    void customersCannotReadRentalHistoryEvenTheirOwn() throws Exception {
        User rider = saveUser(Role.USER);
        Booking b = booking(rider, saveScooter(), BookingStatus.ACTIVE, LocalDateTime.now().minusHours(1));
        mvc.perform(as(rider, get("/api/admin/gps/bookings/" + b.getBookingId() + "/history")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/gps/bookings/" + b.getBookingId() + "/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anUnknownBookingIs404() throws Exception {
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/gps/bookings/" + UUID.randomUUID() + "/history")))
                .andExpect(status().isNotFound());
    }

    @Test
    void liveTrackingNamesTheRentalSoItsHistoryCanBeOpened() throws Exception {
        Booking b = booking(saveUser(Role.USER), saveScooter(), BookingStatus.ACTIVE, LocalDateTime.now().minusHours(1));
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/gps/live")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].activeBookingId", hasItem(b.getBookingId().toString())));
    }
}
