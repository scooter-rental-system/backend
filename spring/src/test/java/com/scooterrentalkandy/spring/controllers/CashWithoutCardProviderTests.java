package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash without PAYMENTS_PROVIDER (BE-044): no payment gateway exists in this context, card payments still answer
 * 503 exactly as before, and a rental paid by bank transfer and settled in cash at the counter (transfer approved,
 * rental, end, final cash balance recorded by an admin) still works because it never needs a provider. Embedded
 * PostgreSQL; each test rolls back.
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
    private PaymentReceiptRepository receipts;

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

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(User user, B request) {
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

    /** Bank transfer on, with its details set. */
    private void enableBankTransfer(User admin) throws Exception {
        mvc.perform(as(admin, put("/api/admin/payment-settings")).contentType(MediaType.APPLICATION_JSON).content("""
                        {"qrEnabled": false, "bankEnabled": true, "bankName": "Bank of Ceylon",
                         "bankAccountHolder": "Scooter Rent Kandy", "bankAccountNumber": "0001234567",
                         "bankBranch": "Kandy"}"""))
                .andExpect(status().isOk());
    }

    @Test
    void aTransferRentalSettledInCashWorksWithoutAProvider() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        enableBankTransfer(admin);
        UUID id = signedBooking(customer);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/transfer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"BANK_TRANSFER\"}")).andExpect(status().isOk());
        mvc.perform(as(customer, multipart("/api/bookings/" + id + "/receipts")
                        .file(new MockMultipartFile("receipt", "receipt.png", "image/png", RepairPhotoTests.PNG))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        UUID receiptId = receipts.findPendingByBookingId(id).orElseThrow().getPaymentReceiptId();
        mvc.perform(as(admin, post("/api/admin/payment-receipts/" + receiptId + "/approve"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 250}"))
                .andExpect(status().isOk());
        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        mvc.perform(as(admin, post("/api/admin/bookings/" + id + "/handover")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"startOdometerKm\": 5000.0}")).andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + id + "/start"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        Booking b = bookings.findById(id).orElseThrow();
        b.setActualStartTime(LocalDateTime.now().minusHours(26).minusMinutes(30)); // 1 day + 3 hours = 3,250
        b.setCustomerStartConfirmedAt(b.getActualStartTime());
        bookings.saveAndFlush(b);
        mvc.perform(as(admin, put("/api/bookings/" + id + "/complete")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endOdometerKm\": 5100.0}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.payment.balanceDue").value(3000.00));
        mvc.perform(as(admin, post("/api/admin/bookings/" + id + "/cash/balance"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 3000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(3250.00));

        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(payments.findByBooking_BookingId(id).orElseThrow().getMethod())
                .isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void cancellingAPendingTransferBookingNeedsNoProvider() throws Exception {
        User customer = saveUser(Role.USER);
        enableBankTransfer(saveUser(Role.ADMIN));
        UUID id = signedBooking(customer);
        mvc.perform(as(customer, post("/api/bookings/" + id + "/transfer")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"method\": \"BANK_TRANSFER\"}")).andExpect(status().isOk());

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }
}
