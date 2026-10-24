package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.PaymentGateway;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
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
 * The application as deployed without PAYMENTS_PROVIDER: there is no payment gateway at all (this context does
 * not import the test gateway), payment operations answer 503, the configuration says payments are unavailable,
 * and the former public mock-Stripe endpoint does not exist. Nothing falls back to a fake provider. Embedded
 * PostgreSQL as in {@link PostgresIntegrationTest}; each test rolls back.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
class PaymentsUnavailableTests {

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

    private User saveUser() {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("nopay." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("No Pay " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("N" + id);
        u.setRole(Role.USER);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private UUID signedBooking(User customer) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Scooter s = new Scooter();
        s.setCode("NP-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("NPP-" + id);
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
    void thereIsNoPaymentGateway() {
        assertThat(context.getBeanNamesForType(PaymentGateway.class)).isEmpty();
    }

    @Test
    void theConfigurationSaysCardPaymentsAreUnavailable() throws Exception {
        mvc.perform(as(saveUser(), get("/api/payments/config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.publishableKey").doesNotExist())
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    void startingAPaymentIs503AndCreatesNothing() throws Exception {
        User owner = saveUser();
        UUID bookingId = signedBooking(owner);

        mvc.perform(as(owner, post("/api/bookings/" + bookingId + "/payment-intent")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("Card payments are not available: no payment provider is configured"));

        assertThat(payments.findByBooking_BookingId(bookingId)).isEmpty();
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void webhooksAre503() throws Exception {
        mvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": \"evt_1\", \"type\": \"payment_intent.succeeded\"}")
                        .header("Stripe-Signature", "t=1,v1=anything"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void theMockStripeEndpointNoLongerExists() throws Exception {
        String confirm = "/mock-stripe/v1/payment_intents/pi_any/confirm";
        String body = "{\"clientSecret\": \"pi_any_secret\", \"paymentMethod\": \"pm_any\"}";

        mvc.perform(post(confirm).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(saveUser(), post(confirm)).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
    }
}
