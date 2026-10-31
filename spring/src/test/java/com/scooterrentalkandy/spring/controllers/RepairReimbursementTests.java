package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.ReportService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approved repair reimbursement in the real final settlement (BE-043). A rental paid through the test provider runs
 * 26.5 hours with the odometer unchanged (no distance): 1 day + 3 extra hours = LKR 3,250 subtotal, LKR 250 paid at booking. Only the
 * admin-approved amount of a RESOLVED self-arranged report reduces the final bill, never below zero; the customer's
 * claimed amount never does. The balance charged (or refunded) is the adjusted total less what was paid.
 */
@AutoConfigureMockMvc
@Transactional
class RepairReimbursementTests extends PostgresIntegrationTest {

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
    private TestPaymentGateway gateway;

    @Autowired
    private ReportService reports;

    @Autowired
    private EntityManager em;

    @Autowired
    private JwtService jwt;

    private User customer;
    private User admin;

    @BeforeEach
    void setUp() {
        gateway.reset();
        customer = saveUser(Role.USER);
        admin = saveUser(Role.ADMIN);
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("reimb." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Reimbursement Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("reimb" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /**
     * A rental paid through the provider (LKR 250 initial charge), handed over at an odometer of 1000.0 km and
     * started by the customer, 26.5 hours in.
     */
    private UUID paidRentalUnderWay() throws Exception {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("RB-" + id);
        s.setModel("Reimbursement Dio");
        s.setPlateNumber("RBP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        scooters.saveAndFlush(s);
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                        .formatted(s.getScooterId(), LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/contract/sign")),
                "{\"signedName\": \"%s\", \"agree\": true}".formatted(customer.getFullName()))).andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/payment-intent"))).andExpect(status().isOk());
        mvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content(gateway.deliver(gateway.succeeded(gateway.intents().getLast(), "pm_test_saved", "4242")))
                        .header("Stripe-Signature", TestPaymentGateway.SIGNATURE))
                .andExpect(status().isOk());
        assertThat(bookings.findById(bookingId).orElseThrow().getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + bookingId + "/handover")),
                "{\"startOdometerKm\": 1000.0}")).andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/start"))).andExpect(status().isOk());
        Booking b = bookings.findById(bookingId).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        b.setActualStartTime(LocalDateTime.now().minusHours(26).minusMinutes(30));
        b.setCustomerStartConfirmedAt(b.getActualStartTime());
        bookings.saveAndFlush(b);
        return bookingId;
    }

    /** The customer's report; returns its id. */
    private String report(UUID bookingId, boolean selfArranged, String claimed) throws Exception {
        String body = """
                {"description": "Chain snapped near Gampola", "selfArranged": %s, "claimedAmount": %s}"""
                .formatted(selfArranged, claimed);
        String response = mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/repairs")), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions review(String reportId, String status, String approved) throws Exception {
        return mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                "{\"status\": \"%s\", \"adminNote\": \"Receipt checked\", \"approvedAmount\": %s}"
                        .formatted(status, approved)));
    }

    /** An admin ends the rental; the odometer is unchanged since the handover (no distance). */
    private ResultActions end(UUID bookingId) throws Exception {
        return mvc.perform(json(as(admin, put("/api/bookings/" + bookingId + "/complete")), "{\"endOdometerKm\": 1000.0}"));
    }

    private Payment payment(UUID bookingId) {
        em.flush();
        return payments.findByBooking_BookingId(bookingId).orElseThrow();
    }

    @Test
    void withoutARepairTheFinalBillIsTheRentalSubtotal() throws Exception {
        UUID id = paidRentalUnderWay();

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(3250.00));
        assertThat(gateway.charges()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("3000.00"));
    }

    @Test
    void theCustomersClaimedAmountAloneDoesNotChangeTheBill() throws Exception {
        UUID id = paidRentalUnderWay();
        String repair = report(id, true, "2000");
        end(id).andExpect(status().isConflict()); // resolve the report first

        review(repair, "RESOLVED", "null").andExpect(status().isOk());
        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.totalCost").value(3250.00));
    }

    @Test
    void anApprovalOnAReportNotYetResolvedBlocksTheFinalBill() throws Exception {
        UUID id = paidRentalUnderWay();
        review(report(id, true, "2000"), "UNDER_REVIEW", "1500").andExpect(status().isOk());

        end(id).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This rental has unresolved repair reports. Resolve them before calculating the final bill."));
        assertThat(gateway.charges()).isEmpty();
    }

    @Test
    void anApprovedResolvedReimbursementReducesTheBillAndTheBalanceCharged() throws Exception {
        UUID id = paidRentalUnderWay();
        review(report(id, true, "2000"), "RESOLVED", "1500").andExpect(status().isOk());

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(1750.00));

        // Adjusted total 1,750 less the 250 paid at booking: 1,500 charged to the saved card.
        assertThat(gateway.charges()).singleElement().satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("1500.00"));
        assertThat(payment(id).getAmount()).isEqualByComparingTo("1750.00");
        String invoices = mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("FINAL"))
                .andExpect(jsonPath("$[0].subtotal").value(3250.00))
                .andExpect(jsonPath("$[0].total").value(1750.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(lastLine(invoices, "description")).isEqualTo("Approved repair reimbursement");
        assertThat(new BigDecimal(lastLine(invoices, "amount"))).isEqualByComparingTo("-1500.00");
    }

    @Test
    void severalApprovedReportsAreAddedUpEachOnce() throws Exception {
        UUID id = paidRentalUnderWay();
        review(report(id, true, "1200"), "RESOLVED", "1000").andExpect(status().isOk());
        String second = report(id, true, "600");
        review(second, "RESOLVED", "500").andExpect(status().isOk());
        review(second, "RESOLVED", "500").andExpect(status().isOk()); // saved twice: still counted once
        String declined = report(id, true, "900");
        review(declined, "UNDER_REVIEW", "900").andExpect(status().isOk());
        review(report(id, false, "null"), "RESOLVED", "null").andExpect(status().isOk());
        end(id).andExpect(status().isConflict());
        review(declined, "RESOLVED", "null").andExpect(status().isOk()); // resolved without a reimbursement

        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.totalCost").value(1750.00));
    }

    @Test
    void aReimbursementAboveTheSubtotalBringsTheBillToZeroAndRefundsWhatWasPaid() throws Exception {
        UUID id = paidRentalUnderWay();
        review(report(id, true, "5000"), "RESOLVED", "5000").andExpect(status().isOk());

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(0.00));

        assertThat(gateway.charges()).isEmpty();
        assertThat(gateway.refunds()).singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("250.00"));
        // Nothing is owed: the LKR 250 paid at booking is refunded in full.
        assertThat(payment(id).getStatus()).isEqualTo(com.scooterrentalkandy.spring.entity.enums.PaymentStatus.REFUNDED);
        String invoices = mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].subtotal").value(3250.00))
                .andExpect(jsonPath("$[0].total").value(0.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(lastLine(invoices, "amount"))).isEqualByComparingTo("-3250.00");
        assertThat(lastLine(invoices, "description"))
                .isEqualTo("Approved repair reimbursement (LKR 5000.00 approved; limited to the rental subtotal)");
    }

    @Test
    void theReimbursementCannotChangeAfterTheFinalBillIsCalculated() throws Exception {
        UUID id = paidRentalUnderWay();
        String repair = report(id, true, "2000");
        review(repair, "RESOLVED", "1500").andExpect(status().isOk());
        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.totalCost").value(1750.00));

        review(repair, "RESOLVED", "2000").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith(
                        "The final bill for this rental has already been calculated")));
        review(repair, "UNDER_REVIEW", "1500").andExpect(status().isConflict());
        // Saving it again without moving the reimbursement is still allowed.
        review(repair, "RESOLVED", "1500").andExpect(status().isOk());
    }

    @Test
    void theCsvShowsTheSubtotalTheReimbursementAndTheAdjustedTotal() throws Exception {
        UUID withRepair = paidRentalUnderWay();
        review(report(withRepair, true, "2000"), "RESOLVED", "1500").andExpect(status().isOk());
        end(withRepair).andExpect(status().isOk());
        UUID without = paidRentalUnderWay();
        end(without).andExpect(status().isOk());
        em.flush();

        String csv = reports.bookingsCsv(LocalDate.now(), LocalDate.now());
        List<String> header = cells(csv.lines().findFirst().orElseThrow());
        List<String> a = row(csv, bookings.findById(withRepair).orElseThrow().getReference());
        assertThat(a.get(header.indexOf("Rental Subtotal (LKR)"))).isEqualTo("3250.00");
        assertThat(a.get(header.indexOf("Approved Repair Reimbursement (LKR)"))).isEqualTo("1500.00");
        assertThat(a.get(header.indexOf("Total Amount (LKR)"))).isEqualTo("1750.00");
        List<String> b = row(csv, bookings.findById(without).orElseThrow().getReference());
        assertThat(b.get(header.indexOf("Rental Subtotal (LKR)"))).isEqualTo("3250.00");
        assertThat(b.get(header.indexOf("Approved Repair Reimbursement (LKR)"))).isEqualTo("0.00");
        assertThat(b.get(header.indexOf("Total Amount (LKR)"))).isEqualTo("3250.00");
    }

    /** A field of the last line of the first invoice. */
    private static String lastLine(String invoicesJson, String field) {
        List<Object> lines = JsonPath.read(invoicesJson, "$[0].lines");
        return String.valueOf(JsonPath.<Object>read(lines.getLast(), "$." + field));
    }

    private static List<String> row(String csv, String reference) {
        return cells(csv.lines().filter(l -> l.startsWith("\"" + reference + "\"")).findFirst().orElseThrow());
    }

    /** The cells of one CSV record (quoted cells may contain commas and doubled quotes). */
    private static List<String> cells(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString());
        return cells;
    }
}
