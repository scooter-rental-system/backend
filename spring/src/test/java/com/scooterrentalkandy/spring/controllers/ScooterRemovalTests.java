package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.Rentals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-050: removing a scooter from the fleet (DELETE /api/admin/scooters/{id}) over HTTP, through the real security
 * filter chain and embedded PostgreSQL. Scooters, users and bookings are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class ScooterRemovalTests extends PostgresIntegrationTest {

    private static final String SCOOTERS = "/api/admin/scooters/";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("fleet." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Fleet " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("F" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter(ScooterStatus status) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("R-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("RP-" + id);
        s.setStatus(status);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setTotalMileage(new BigDecimal("100.00"));
        return scooters.saveAndFlush(s);
    }

    /** Fixture: a booking in the given state on the scooter (the scooter's own status is left as it is). */
    private void book(Scooter scooter, BookingStatus status) {
        Booking b = new Booking();
        b.setReference("BK-RM" + unique());
        b.setUser(saveUser(Role.USER));
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(LocalDateTime.now().plusDays(1));
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        if (status == BookingStatus.COMPLETED) { // a finished rental has its end and final bill (V5)
            b.setEndTime(b.getStartTime().plusDays(1));
            b.setTotalCost(new BigDecimal("2500.00"));
        }
        b.setPickupLocation("Test pickup");
        bookings.saveAndFlush(b);
    }

    private MockHttpServletRequestBuilder as(Role role, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(saveUser(role)));
    }

    private MockHttpServletRequestBuilder remove(Scooter scooter) {
        return as(Role.ADMIN, delete(SCOOTERS + scooter.getScooterId()));
    }

    private boolean isDeleted(Scooter scooter) {
        return scooters.findById(scooter.getScooterId()).orElseThrow().isDeleted();
    }

    @Test
    void aScooterHeldByABookingCannotBeRemoved() throws Exception {
        for (BookingStatus holding : BookingStatus.BLOCKING) {
            Scooter scooter = saveScooter(ScooterStatus.AVAILABLE);
            book(scooter, holding);

            mvc.perform(remove(scooter))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value(
                            "Scooter has a pending or active booking; cancel it before removing the scooter"));
            assertThat(isDeleted(scooter)).as(holding.name()).isFalse();
        }
    }

    @Test
    void finishedOrCancelledBookingsDoNotStopRemoval() throws Exception {
        for (BookingStatus closed : new BookingStatus[] {BookingStatus.COMPLETED, BookingStatus.CANCELLED}) {
            Scooter scooter = saveScooter(ScooterStatus.AVAILABLE);
            book(scooter, closed);

            mvc.perform(remove(scooter))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.deleted").value(true));
            assertThat(isDeleted(scooter)).as(closed.name()).isTrue();
        }
    }

    @Test
    void aScooterWithoutBookingsIsRemovedAndCanBeRestored() throws Exception {
        Scooter scooter = saveScooter(ScooterStatus.AVAILABLE);

        mvc.perform(remove(scooter))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
        mvc.perform(as(Role.ADMIN, post(SCOOTERS + scooter.getScooterId() + "/restore")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(false));
        assertThat(isDeleted(scooter)).isFalse();
    }

    @Test
    void aRentedScooterIsStillRefused() throws Exception {
        Scooter rented = saveScooter(ScooterStatus.RENTED);

        mvc.perform(remove(rented))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Scooter is out on a rental; it is released when the rental ends"));
        assertThat(isDeleted(rented)).isFalse();
    }

    @Test
    void unknownScooterIs404AndCustomersAreForbidden() throws Exception {
        mvc.perform(as(Role.ADMIN, delete(SCOOTERS + UUID.randomUUID())))
                .andExpect(status().isNotFound());

        Scooter scooter = saveScooter(ScooterStatus.AVAILABLE);
        mvc.perform(as(Role.USER, delete(SCOOTERS + scooter.getScooterId())))
                .andExpect(status().isForbidden());
        assertThat(isDeleted(scooter)).isFalse();
    }
}
