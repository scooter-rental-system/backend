package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.PaymentGateway;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash without PAYMENTS_PROVIDER (BE-044): no payment gateway exists in this context, card payments still answer
 * 503 exactly as before, and the whole cash lifecycle (choose cash, admin confirms, rental, end, final cash balance)
 * still works because it never needs a provider. Embedded PostgreSQL; each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class CashWithoutCardProviderTests {

    @DynamicPropertySource
    static void embeddedDatabase(DynamicPropertyRegistry registry) {
        PostgresIntegrationTest.embeddedDatabase(registry);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JwtService jwt;

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("nocard." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("No Card " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("nocard" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private UUID signedBooking(User customer) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Scooter s = new Scooter();
        s.setCode("NC-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("NCP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        scooters.saveAndFlush(s);
        String response = mvc.perform(as(customer, post("/api/bookings")).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                                .formatted(s.getScooterId(),
                                        LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(response, "$.id"));
        mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/contract/sign"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"signedName": "%s", "agree": true}""".formatted(customer.getFullName())))
                .andExpect(status().isOk());
        return bookingId;
    }

    @Test
    void cardStaysUnavailableAndFailsClosed() throws Exception {
        assertThat(context.getBeanNamesForType(PaymentGateway.class)).isEmpty();
        User customer = saveUser(Role.USER);
        UUID id = signedBooking(customer);

        mvc.perform(as(customer, get("/api/payments/config"))).andExpect(jsonPath("$.available").value(false));
        mvc.perform(as(customer, post("/api/bookings/" + id + "/payment-intent")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Card payments are not available: no payment provider is configured"));
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }

    @Test
    void theWholeCashLifecycleWorksWithoutAProvider() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        UUID id = signedBooking(customer);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cash"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(as(admin, post("/api/admin/bookings/" + id + "/cash/initial"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Booking b = bookings.findById(id).orElseThrow();
        b.setStartTime(LocalDateTime.now().minusHours(26).minusMinutes(30)); // 1 day + 3 hours = 3,250
        bookings.saveAndFlush(b);
        mvc.perform(as(customer, put("/api/bookings/" + id + "/complete"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.payment.balanceDue").value(3000.00));
        mvc.perform(as(admin, post("/api/admin/bookings/" + id + "/cash/balance"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(3250.00));

        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(payments.findByBooking_BookingId(id).orElseThrow().getMethod()).isEqualTo(PaymentMethod.CASH);
    }

    @Test
    void cancellingAPendingCashBookingNeedsNoProvider() throws Exception {
        User customer = saveUser(Role.USER);
        UUID id = signedBooking(customer);
        mvc.perform(as(customer, post("/api/bookings/" + id + "/cash"))).andExpect(status().isOk());

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
