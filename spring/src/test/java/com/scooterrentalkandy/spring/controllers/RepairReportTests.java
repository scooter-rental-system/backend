package com.scooterrentalkandy.spring.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
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
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Repair and breakdown reports: the rider reports on their own rental in progress, admins review them and may record
 * an approved reimbursement for a self-arranged repair. Nothing here moves money.
 */
@AutoConfigureMockMvc
@Transactional
class RepairReportTests extends PostgresIntegrationTest {

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
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("repair." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Repair Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("repair" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private Booking rental(User customer, BookingStatus status, LocalDateTime start) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RP-" + id);
        s.setModel("Repair Dio");
        s.setPlateNumber("RPP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setStatus(status == BookingStatus.ACTIVE ? ScooterStatus.RENTED : ScooterStatus.AVAILABLE);
        scooters.saveAndFlush(s);
        Booking b = new Booking();
        b.setReference("BK-RP" + id);
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(status);
        b.setStartTime(start);
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setContractSigned(true);
        b.setPickupLocation("Kandy office");
        if (status == BookingStatus.COMPLETED) {
            // A completed booking carries its final fields (ck_bookings_final_fields).
            b.setEndTime(start.plusHours(3));
            b.setDistanceKm(new BigDecimal("10.00"));
            b.setTotalCost(new BigDecimal("750.00"));
        }
        return bookings.saveAndFlush(b);
    }

    private Booking inProgress(User customer) {
        return rental(customer, BookingStatus.ACTIVE, LocalDateTime.now().minusHours(2));
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private String report(User customer, Booking b, String body) throws Exception {
        String response = mvc.perform(as(customer, json(post("/api/bookings/" + b.getBookingId() + "/repairs"), body)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    @Test
    void theRiderReportsAProblemWithTheirRentalInProgress() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);
        mvc.perform(as(customer, json(post("/api/bookings/" + b.getBookingId() + "/repairs"),
                        "{\"description\": \"Front tyre puncture near Peradeniya\", \"selfArranged\": false}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"))
                .andExpect(jsonPath("$.bookingReference").value(b.getReference()))
                .andExpect(jsonPath("$.scooterCode").value(b.getScooter().getCode()))
                .andExpect(jsonPath("$.customerName").value(customer.getFullName()))
                .andExpect(jsonPath("$.reportedAt").isNotEmpty())
                .andExpect(jsonPath("$.approvedAmount").doesNotExist());
        mvc.perform(as(customer, get("/api/bookings/" + b.getBookingId() + "/repairs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void aSelfArrangedRepairCanCarryAClaim() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);
        mvc.perform(as(customer, json(post("/api/bookings/" + b.getBookingId() + "/repairs"),
                        "{\"description\": \"Chain fixed at a local garage\", \"selfArranged\": true, "
                                + "\"claimedAmount\": 1800.50}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.selfArranged").value(true))
                .andExpect(jsonPath("$.claimedAmount").value(1800.50));
    }

    @Test
    void validatesTheReport() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);
        String url = "/api/bookings/" + b.getBookingId() + "/repairs";
        mvc.perform(as(customer, json(post(url), "{\"description\": \"  \", \"selfArranged\": false}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.description").exists());
        mvc.perform(as(customer, json(post(url), "{\"description\": \"x\", \"selfArranged\": true, \"claimedAmount\": -1}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.claimedAmount").exists());
        mvc.perform(as(customer, json(post(url), "{\"description\": \"x\", \"selfArranged\": false, \"claimedAmount\": 10}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A claimed amount is only for a repair you arranged yourself"));
    }

    @Test
    void onlyARentalInProgressCanBeReported() throws Exception {
        User customer = saveUser(Role.USER);
        String body = "{\"description\": \"Brakes feel soft\", \"selfArranged\": false}";
        for (Booking b : new Booking[] {
                rental(customer, BookingStatus.PENDING, LocalDateTime.now().minusHours(1)),
                rental(customer, BookingStatus.READY_FOR_PICKUP, LocalDateTime.now().minusHours(1)),
                rental(customer, BookingStatus.COMPLETED, LocalDateTime.now().minusDays(2))}) {
            mvc.perform(as(customer, json(post("/api/bookings/" + b.getBookingId() + "/repairs"), body)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("A repair can be reported only while the rental is in progress"));
        }
    }

    @Test
    void nobodyElseCanReportOrReadAnotherCustomersReports() throws Exception {
        User owner = saveUser(Role.USER);
        User other = saveUser(Role.USER);
        Booking b = inProgress(owner);
        report(owner, b, "{\"description\": \"Horn not working\", \"selfArranged\": false}");
        mvc.perform(as(other, json(post("/api/bookings/" + b.getBookingId() + "/repairs"),
                        "{\"description\": \"Not mine\", \"selfArranged\": false}")))
                .andExpect(status().isNotFound());
        mvc.perform(as(other, get("/api/bookings/" + b.getBookingId() + "/repairs"))).andExpect(status().isNotFound());
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/bookings/" + b.getBookingId() + "/repairs")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(as(other, get("/api/admin/repairs"))).andExpect(status().isForbidden());
    }

    @Test
    void anAdminUpdatesTheStatusAndRecordsAnApprovedReimbursement() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        String id = report(customer, inProgress(customer),
                "{\"description\": \"Battery died, fixed by a mechanic\", \"selfArranged\": true, \"claimedAmount\": 2500}");

        mvc.perform(as(admin, get("/api/admin/repairs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].status").value("REPORTED"));
        mvc.perform(as(admin, json(put("/api/admin/repairs/" + id),
                        "{\"status\": \"RESOLVED\", \"adminNote\": \"Receipt checked\", \"approvedAmount\": 2000}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.adminNote").value("Receipt checked"))
                .andExpect(jsonPath("$.approvedAmount").value(2000));
        mvc.perform(as(customer, json(put("/api/admin/repairs/" + id), "{\"status\": \"RESOLVED\"}")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reimbursementIsOnlyForASelfArrangedRepairAndNeverNegative() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        String id = report(customer, inProgress(customer), "{\"description\": \"Flat tyre\", \"selfArranged\": false}");
        mvc.perform(as(admin, json(put("/api/admin/repairs/" + id),
                        "{\"status\": \"ASSISTANCE_ARRANGED\", \"approvedAmount\": 500}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(admin, json(put("/api/admin/repairs/" + id), "{\"status\": \"UNDER_REVIEW\", \"approvedAmount\": -5}")))
                .andExpect(status().isBadRequest());
        mvc.perform(as(admin, json(put("/api/admin/repairs/" + id), "{\"status\": \"ASSISTANCE_ARRANGED\"}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ASSISTANCE_ARRANGED"));
    }

    @Test
    void theSupportContactIsOnlyWhatIsConfigured() throws Exception {
        mvc.perform(as(saveUser(Role.USER), get("/api/support/contact")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").doesNotExist())
                .andExpect(jsonPath("$.email").doesNotExist());
        mvc.perform(get("/api/support/contact")).andExpect(status().isUnauthorized());
    }
}
