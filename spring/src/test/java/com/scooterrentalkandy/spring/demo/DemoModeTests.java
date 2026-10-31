package com.scooterrentalkandy.spring.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.PaymentGateway;
import com.scooterrentalkandy.spring.services.PricingService;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Viva demo mode as it runs locally: the {@code demo} profile and no payment provider (this context does not import
 * the test gateway). Admins can make a booking ready for pickup without a payment (the rental then starts through
 * the normal handover and the customer's start), end it and close it; payments themselves stay
 * unavailable, GPS and repairs follow their normal rules, and nothing is recorded as paid. Each test rolls back.
 */
@SpringBootTest
@ActiveProfiles({"test", "demo"})
@AutoConfigureMockMvc
@Transactional
class DemoModeTests {

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
    private GpsPingRepository pings;

    @Autowired
    private InvoiceRepository invoices;

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
        u.setEmail("demo." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Demo Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("demo" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    /** A booking made through the normal API, two days ahead, optionally with the agreement signed. */
    private UUID booking(User customer, boolean signed) throws Exception {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("DM-" + id);
        s.setModel("Demo Dio");
        s.setPlateNumber("DMP-" + id);
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
        if (signed) {
            mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/contract/sign"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"signedName\": \"%s\", \"agree\": true}".formatted(customer.getFullName())))
                    .andExpect(status().isOk());
        }
        return bookingId;
    }

    private ResultActions demo(User user, String action, UUID bookingId) throws Exception {
        return mvc.perform(as(user, post("/api/admin/demo/bookings/" + bookingId + "/" + action)));
    }

    /**
     * Demo activation (no payment), then the normal handover at an odometer of 1000.0 km and the customer's start;
     * with {@code dayAgo}, the rental is then moved to have started 26.5 hours ago.
     */
    private void startDemoRental(User admin, User customer, UUID bookingId, boolean dayAgo) throws Exception {
        demo(admin, "activate", bookingId).andExpect(status().isOk());
        handOverAndStart(admin, customer, bookingId, dayAgo);
    }

    /** The normal handover at an odometer of 1000.0 km and the customer's start, on a booking ready for pickup. */
    private void handOverAndStart(User admin, User customer, UUID bookingId, boolean dayAgo) throws Exception {
        mvc.perform(as(admin, post("/api/admin/bookings/" + bookingId + "/handover"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"startOdometerKm\": 1000.0}"))
                .andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/start"))).andExpect(status().isOk());
        if (dayAgo) {
            Booking b = reload(bookingId);
            b.setActualStartTime(LocalDateTime.now().minusHours(26).minusMinutes(30));
            b.setCustomerStartConfirmedAt(b.getActualStartTime());
            bookings.saveAndFlush(b);
        }
    }

    /** Ends the demo rental with the ending odometer. */
    private ResultActions demoEnd(User admin, UUID bookingId, String endOdometerKm) throws Exception {
        return mvc.perform(as(admin, post("/api/admin/demo/bookings/" + bookingId + "/end"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"endOdometerKm\": " + endOdometerKm + "}"));
    }

    private ResultActions location(User rider, UUID bookingId, String lat, String lng) throws Exception {
        return mvc.perform(as(rider, post("/api/bookings/" + bookingId + "/location"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"latitude\": %s, \"longitude\": %s}".formatted(lat, lng)));
    }

    private Booking reload(UUID id) {
        return bookings.findWithDetailsByBookingId(id).orElseThrow();
    }

    @Test
    void theDemoBeansExistAndTheStatusSaysSo() throws Exception {
        assertThat(context.getBeansOfType(DemoLifecycleService.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(PaymentGateway.class)).as("still no payment provider").isEmpty();
        mvc.perform(as(saveUser(Role.USER), get("/api/demo/status")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo").value(true))
                .andExpect(jsonPath("$.message").value("Viva demo mode: no real payments are processed."));
    }

    @Test
    void onlyAdminsCanUseTheDemoActions() throws Exception {
        User customer = saveUser(Role.USER);
        UUID id = booking(customer, true);

        mvc.perform(post("/api/admin/demo/bookings/" + id + "/activate")).andExpect(status().isUnauthorized());
        for (String action : List.of("activate", "end", "close")) {
            demo(customer, action, id).andExpect(status().isForbidden());
        }
        assertThat(reload(id).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void anAdminMakesASignedPendingBookingReadyForPickupWithoutAnyPayment() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        UUID id = booking(customer, true);

        // Only the payment is skipped: the booking waits for the handover and the customer's start like any other.
        demo(admin, "activate", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.stage").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.actualStartTime").doesNotExist())
                .andExpect(jsonPath("$.payment").doesNotExist());
        assertThat(reload(id).getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);

        handOverAndStart(admin, customer, id, false);
        mvc.perform(as(customer, get("/api/bookings/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("IN_PROGRESS"));
        Booking b = reload(id);
        assertThat(b.getActualStartTime()).as("starts at the customer's confirmation")
                .isBeforeOrEqualTo(LocalDateTime.now());
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.RENTED);
        assertThat(payments.findByBooking_BookingId(id)).as("no payment record of any kind").isEmpty();
        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(id)).as("no GPS points generated").isEmpty();
    }

    @Test
    void aSuperAdminCanActivateToo() throws Exception {
        UUID id = booking(saveUser(Role.USER), true);

        demo(saveUser(Role.SUPER_ADMIN), "activate", id).andExpect(status().isOk());
    }

    @Test
    void ineligibleBookingsAreNotActivated() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);

        UUID unsigned = booking(customer, false);
        demo(admin, "activate", unsigned).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "The customer must sign the rental agreement before the demo activation"));

        UUID cancelled = booking(saveUser(Role.USER), true);
        Booking c = reload(cancelled);
        c.setStatus(BookingStatus.CANCELLED);
        c.setCancelledAt(LocalDateTime.now());
        bookings.saveAndFlush(c);
        demo(admin, "activate", cancelled).andExpect(status().isConflict());

        UUID active = booking(saveUser(Role.USER), true);
        demo(admin, "activate", active).andExpect(status().isOk());
        demo(admin, "activate", active).andExpect(status().isConflict());

        demo(admin, "activate", UUID.randomUUID()).andExpect(status().isNotFound());
        assertThat(reload(unsigned).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(reload(cancelled).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void aCompletedBookingIsNotActivatedEndedOrClosed() throws Exception {
        User admin = saveUser(Role.ADMIN);
        UUID id = booking(saveUser(Role.USER), true);
        Booking b = reload(id);
        b.setStatus(BookingStatus.COMPLETED);
        b.setStartTime(LocalDateTime.now().minusHours(5));
        b.setActualStartTime(b.getStartTime());
        b.setEndTime(LocalDateTime.now().minusHours(2));
        b.setDistanceKm(new BigDecimal("10.00"));
        b.setTotalCost(new BigDecimal("750.00"));
        bookings.saveAndFlush(b);

        for (String action : List.of("activate", "end", "close")) {
            demo(admin, action, id).andExpect(status().isConflict());
        }
        assertThat(reload(id).getStatus()).isEqualTo(BookingStatus.COMPLETED);
    }

    @Test
    void aPendingBookingCannotBeEndedOrClosedAsADemoRental() throws Exception {
        User admin = saveUser(Role.ADMIN);
        UUID id = booking(saveUser(Role.USER), true);

        demo(admin, "end", id).andExpect(status().isConflict());
        demo(admin, "close", id).andExpect(status().isConflict());
        assertThat(reload(id).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void paymentsStayUnavailableInDemoMode() throws Exception {
        User customer = saveUser(Role.USER);
        UUID id = booking(customer, true);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/payment-intent")))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(as(customer, get("/api/payments/config")))
                .andExpect(jsonPath("$.available").value(false));
        mvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\": \"evt_1\", \"type\": \"payment_intent.succeeded\"}")
                        .header("Stripe-Signature", "t=1,v1=anything"))
                .andExpect(status().isServiceUnavailable());
        assertThat(reload(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }

    @Test
    void gpsKeepsItsNormalRulesBeforeDuringAndAfterTheDemoRental() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        UUID id = booking(customer, true);

        location(customer, id, "7.2906", "80.6337").andExpect(status().isConflict());

        demo(admin, "activate", id).andExpect(status().isOk());
        location(customer, id, "7.2906", "80.6337").andExpect(status().isConflict()); // ready, not started
        handOverAndStart(admin, customer, id, false);
        location(saveUser(Role.USER), id, "7.2906", "80.6337").andExpect(status().isNotFound());
        location(customer, id, "7.2906", "80.6337").andExpect(status().isAccepted());

        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(id)).singleElement().satisfies(p -> {
            assertThat(p.getLatitude()).isEqualByComparingTo("7.2906");
            assertThat(p.getLongitude()).isEqualByComparingTo("80.6337");
        });
        mvc.perform(as(admin, get("/api/admin/gps/bookings/" + id + "/history")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1));

        demoEnd(admin, id, "1000.0").andExpect(status().isOk());
        location(customer, id, "7.2910", "80.6340").andExpect(status().isConflict());
        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(id)).hasSize(1);
    }

    @Test
    void theRiderReportsARepairOnTheDemoRental() throws Exception {
        User customer = saveUser(Role.USER);
        UUID id = booking(customer, true);
        startDemoRental(saveUser(Role.ADMIN), customer, id, false);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/repairs")).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Brake feels soft\", \"selfArranged\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("REPORTED"));
    }

    @Test
    void endingCalculatesTheRealFinalBillAndChargesNothing() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);
        UUID id = booking(customer, true);
        startDemoRental(admin, customer, id, true);
        // The ending odometer is required, as for any rental.
        demo(admin, "end", id).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter the ending odometer reading"));

        // 26.5 hours into the rental: 1 day + 3 started extra hours, odometer unchanged (no distance).
        demoEnd(admin, id, "1000.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(3250.00))
                .andExpect(jsonPath("$.breakdown.chargedDays").value(1))
                .andExpect(jsonPath("$.breakdown.extraHours").value(3))
                .andExpect(jsonPath("$.breakdown.includedKm").value(120))
                .andExpect(jsonPath("$.payment").doesNotExist());

        Booking ended = reload(id);
        BigDecimal expected = pricing.finalBill(ended.getScooter(), PricingService.selectionsOf(ended),
                ended.getActualStartTime(), ended.getEndedAt(), ended.getPendingDistanceKm()).total();
        assertThat(ended.getPendingTotalCost()).isEqualByComparingTo(expected);
        Invoice inv = invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow();
        assertThat(inv.getTotal()).isEqualByComparingTo("3250.00");
        assertThat(inv.getStatus()).as("no paid status").isNull();
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
        demoEnd(admin, id, "1000.0").andExpect(status().isConflict());
    }

    @Test
    void closingReleasesTheScooterAndVoidsTheUnpaidInvoice() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User customer = saveUser(Role.USER);
        UUID id = booking(customer, true);
        startDemoRental(admin, customer, id, false);
        demoEnd(admin, id, "1000.0").andExpect(status().isOk());

        demo(admin, "close", id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.pendingTotalCost").doesNotExist());

        Booking b = reload(id);
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow().getStatus())
                .isEqualTo(Invoice.Status.VOID);
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
        demo(admin, "close", id).andExpect(status().isConflict());
    }

    @Test
    void theDemoFinalBillUsesTheSameApprovedRepairReimbursementRule() throws Exception {
        User customer = saveUser(Role.USER);
        User admin = saveUser(Role.ADMIN);
        UUID id = booking(customer, true);
        startDemoRental(admin, customer, id, true);
        String repair = mvc.perform(as(customer, post("/api/bookings/" + id + "/repairs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Chain snapped\", \"selfArranged\": true, \"claimedAmount\": 2000}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        mvc.perform(as(admin, put("/api/admin/repairs/" + JsonPath.read(repair, "$.id")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\": \"RESOLVED\", \"approvedAmount\": 1500}"))
                .andExpect(status().isOk());

        // 1 day + 3 extra hours = 3,250, less the approved 1,500 (not the claimed 2,000).
        demoEnd(admin, id, "1000.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(1750.00))
                .andExpect(jsonPath("$.payment").doesNotExist());

        Booking ended = reload(id);
        assertThat(ended.getPendingTotalCost()).isEqualByComparingTo(pricing.finalBill(ended.getScooter(),
                PricingService.selectionsOf(ended), ended.getActualStartTime(), ended.getEndedAt(),
                ended.getPendingDistanceKm(), new BigDecimal("1500")).total());
        Invoice inv = invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow();
        assertThat(inv.getSubtotal()).isEqualByComparingTo("3250.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("1750.00");
        assertThat(inv.getLines()).anySatisfy(l -> {
            assertThat(l.getDescription()).isEqualTo("Approved repair reimbursement");
            assertThat(l.getAmount()).isEqualByComparingTo("-1500.00");
        });
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }
}
