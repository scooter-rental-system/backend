package com.scooterrentalkandy.spring.controllers;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.BillingService;
import com.scooterrentalkandy.spring.services.PricingService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The payment and invoice read endpoints over HTTP. Their repository queries fetch the booking's customer; an
 * entity-graph path naming a Booking attribute that does not exist made all four fail with 500. Authorization is
 * unchanged: admin lists are ADMIN/SUPER_ADMIN only, an invoice is visible to its customer and admins. The
 * completed, paid booking is fixture state stored directly. Each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class PaymentReadEndpointsTests extends PostgresIntegrationTest {

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
    private BillingService billing;

    @Autowired
    private PricingService pricing;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("reads." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Reads Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("R" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    /** Fixture: a completed rental with its successful payment and FINAL invoice. */
    private Invoice completedAndInvoiced(User customer) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RD-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("RDP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        scooters.saveAndFlush(s);

        LocalDateTime start = LocalDateTime.now().minusHours(5);
        LocalDateTime end = start.plusHours(2);
        PricingService.FinalBill bill = pricing.finalBill(s, List.of(), start, end, new BigDecimal("10"));
        Booking b = new Booking();
        b.setReference("BK-R" + id);
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(BookingStatus.COMPLETED);
        b.setStartTime(start);
        b.setEndTime(end);
        b.setDistanceKm(bill.distanceKm());
        b.setTotalCost(bill.total());
        b.setContractSigned(true);
        b.setPickupLocation("Test pickup");
        bookings.saveAndFlush(b);

        Payment p = new Payment();
        p.setBooking(b);
        p.setAmount(bill.total());
        p.setMethod(PaymentMethod.CARD);
        p.setStatus(PaymentStatus.SUCCESS);
        p.setProvider("test");
        p.setStripePaymentId("pi_test_reads_" + id);
        p.setPaidAt(end);
        payments.saveAndFlush(p);
        return billing.issueFinalInvoice(b, bill);
    }

    @Test
    void adminsListRecentPaymentsWithTheCustomer() throws Exception {
        User customer = saveUser(Role.USER);
        Invoice inv = completedAndInvoiced(customer);

        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(as(saveUser(role), get("/api/admin/payments")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].bookingId").value(hasItem(inv.getBooking().getBookingId().toString())))
                    .andExpect(jsonPath("$[*].customerName").value(hasItem(customer.getFullName())));
        }
    }

    @Test
    void adminsListRecentInvoicesWithTheCustomer() throws Exception {
        User customer = saveUser(Role.USER);
        Invoice inv = completedAndInvoiced(customer);

        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/invoices")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].invoiceNumber").value(hasItem(inv.getInvoiceNumber())))
                .andExpect(jsonPath("$[*].customerName").value(hasItem(customer.getFullName())));
    }

    @Test
    void customersCannotUseTheAdminLists() throws Exception {
        User customer = saveUser(Role.USER);
        completedAndInvoiced(customer);

        mvc.perform(as(customer, get("/api/admin/payments"))).andExpect(status().isForbidden());
        mvc.perform(as(customer, get("/api/admin/invoices"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/invoices")).andExpect(status().isUnauthorized());
    }

    @Test
    void anInvoiceIsVisibleToItsCustomerAndAdminsOnly() throws Exception {
        User customer = saveUser(Role.USER);
        Invoice inv = completedAndInvoiced(customer);
        String path = "/api/invoices/" + inv.getInvoiceId();

        for (User viewer : new User[] {customer, saveUser(Role.ADMIN)}) {
            mvc.perform(as(viewer, get(path)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.invoiceNumber").value(inv.getInvoiceNumber()))
                    .andExpect(jsonPath("$.customerName").value(customer.getFullName()))
                    .andExpect(jsonPath("$.kind").value("FINAL"))
                    .andExpect(jsonPath("$.paymentStatus").value("SUCCESS"))
                    .andExpect(jsonPath("$.currency").value("LKR"))
                    .andExpect(jsonPath("$.lines.length()").value(2));
        }
        mvc.perform(as(saveUser(Role.USER), get(path))).andExpect(status().isForbidden());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(as(customer, get("/api/invoices/" + UUID.randomUUID()))).andExpect(status().isNotFound());
    }

    @Test
    void aBookingsInvoicesAreVisibleToItsCustomerAndAdminsOnly() throws Exception {
        User customer = saveUser(Role.USER);
        Invoice inv = completedAndInvoiced(customer);
        String path = "/api/bookings/" + inv.getBooking().getBookingId() + "/invoices";

        for (User viewer : new User[] {customer, saveUser(Role.ADMIN)}) {
            mvc.perform(as(viewer, get(path)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(1))
                    .andExpect(jsonPath("$[0].invoiceNumber").value(inv.getInvoiceNumber()))
                    .andExpect(jsonPath("$[0].total").value(inv.getTotal().doubleValue()));
        }
        // Another customer's booking is not revealed.
        mvc.perform(as(saveUser(Role.USER), get(path))).andExpect(status().isNotFound());
    }
}
