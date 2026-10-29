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
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentMethod;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
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
 * Cash at the rental counter (BE-044), with a card provider configured (the test gateway) to show cash never
 * reaches it. The customer chooses cash; only an admin records cash as received or handed back. The default scooter
 * (LKR 2,500 a day, 250 an hour) takes LKR 250 at booking; a rental 73.5 hours in bills 3 days + 2 hours = 8,000.
 * The final cash balance is the adjusted final total (after any approved RESOLVED repair reimbursement) less the
 * cash received.
 */
@AutoConfigureMockMvc
@Transactional
class CashPaymentTests extends PostgresIntegrationTest {

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
    private PaymentTransactionRepository transactions;

    @Autowired
    private NotificationRepository notifications;

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
        u.setEmail("cash." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Cash Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("cash" + id));
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

    private static String amount(String value) {
        return "{\"amount\": " + value + "}";
    }

    private Scooter saveScooter(String daily, String hourly) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("CS-" + id);
        s.setModel("Cash Dio");
        s.setPlateNumber("CSP-" + id);
        s.setDailyRate(new BigDecimal(daily));
        s.setHourlyRate(new BigDecimal(hourly));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    /** A booking with the agreement signed, awaiting payment. */
    private UUID signedBooking(Scooter s) throws Exception {
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                        .formatted(s.getScooterId(), LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/contract/sign")),
                "{\"signedName\": \"%s\", \"agree\": true}".formatted(customer.getFullName()))).andExpect(status().isOk());
        return bookingId;
    }

    private UUID signedBooking() throws Exception {
        return signedBooking(saveScooter("2500.00", "250.00"));
    }

    private ResultActions chooseCash(UUID bookingId) throws Exception {
        return mvc.perform(as(customer, post("/api/bookings/" + bookingId + "/cash")));
    }

    private ResultActions confirm(User who, UUID bookingId, String step, String value) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/bookings/" + bookingId + "/cash/" + step)), amount(value)));
    }

    /** Cash chosen and the initial LKR 250 recorded by the admin; the rental started {@code hoursAgo} hours ago. */
    private UUID cashRentalUnderWay(Scooter s, String initial, long minutesAgo) throws Exception {
        UUID id = signedBooking(s);
        chooseCash(id).andExpect(status().isOk());
        confirm(admin, id, "initial", initial).andExpect(status().isOk());
        Booking b = bookings.findById(id).orElseThrow();
        b.setStartTime(LocalDateTime.now().minusMinutes(minutesAgo));
        bookings.saveAndFlush(b);
        return id;
    }

    /** 73.5 hours in: 3 days + 2 extra hours = LKR 8,000 subtotal, LKR 250 cash received. */
    private UUID cashRentalOf8000() throws Exception {
        return cashRentalUnderWay(saveScooter("2500.00", "250.00"), "250", 73 * 60 + 30);
    }

    private String report(UUID bookingId, boolean selfArranged, String claimed) throws Exception {
        String body = """
                {"description": "Puncture repaired at a roadside shop", "selfArranged": %s, "claimedAmount": %s}"""
                .formatted(selfArranged, claimed);
        String response = mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/repairs")), body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void review(String reportId, String status, String approved) throws Exception {
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                        "{\"status\": \"%s\", \"adminNote\": null, \"approvedAmount\": %s}".formatted(status, approved)))
                .andExpect(status().isOk());
    }

    private ResultActions end(UUID bookingId) throws Exception {
        return mvc.perform(as(customer, put("/api/bookings/" + bookingId + "/complete")));
    }

    private Payment payment(UUID bookingId) {
        em.flush();
        em.clear();
        return payments.findByBooking_BookingId(bookingId).orElseThrow();
    }

    private List<PaymentTransaction> recorded(UUID bookingId) {
        return transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(payment(bookingId).getPaymentId());
    }

    private List<String> subjects(User user) {
        em.flush();
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(),
                Notification.Channel.IN_APP).stream().map(Notification::getSubject).toList();
    }

    private List<String> messages(User user) {
        em.flush();
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(),
                Notification.Channel.IN_APP).stream().map(Notification::getMessage).toList();
    }

    private void assertNoProviderCalls() {
        assertThat(gateway.customers()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
        assertThat(gateway.charges()).isEmpty();
        assertThat(gateway.refunds()).isEmpty();
        assertThat(gateway.cancelled()).isEmpty();
    }

    // ---------------------------------------------------------------- choosing cash

    @Test
    void choosingCashLeavesTheBookingAwaitingPaymentAndRecordsNothingAsPaid() throws Exception {
        UUID id = signedBooking();

        chooseCash(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.amount").value(250.00))
                .andExpect(jsonPath("$.payment.paidAt").doesNotExist());

        Payment p = payment(id);
        assertThat(p.getMethod()).isEqualTo(PaymentMethod.CASH);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getProvider()).isNull();
        assertThat(recorded(id)).isEmpty();
        assertThat(subjects(admin)).anyMatch(s -> s.startsWith("Cash payment pending"));
        assertNoProviderCalls();
    }

    @Test
    void cashNeedsTheSignedAgreementAndTheOwnBooking() throws Exception {
        Scooter s = saveScooter("2500.00", "250.00");
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy"}"""
                        .formatted(s.getScooterId(), LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andReturn().getResponse().getContentAsString();
        UUID unsigned = UUID.fromString(JsonPath.read(created, "$.id"));
        chooseCash(unsigned).andExpect(status().isBadRequest());

        UUID id = signedBooking();
        mvc.perform(as(saveUser(Role.USER), post("/api/bookings/" + id + "/cash"))).andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + id + "/cash")).andExpect(status().isUnauthorized());
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }

    @Test
    void choosingCashClosesAnOpenCardAttemptAndCardCanStillBeChosenAfterCash() throws Exception {
        UUID id = signedBooking();
        mvc.perform(as(customer, post("/api/bookings/" + id + "/payment-intent"))).andExpect(status().isOk());
        String intent = gateway.intents().getLast().id();

        chooseCash(id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.method").value("CASH"));
        assertThat(gateway.cancelled()).containsExactly(intent);
        Payment p = payment(id);
        assertThat(p.getStripePaymentId()).isNull();
        assertThat(p.getProvider()).isNull();

        // Back to card: the normal provider flow, unchanged.
        mvc.perform(as(customer, post("/api/bookings/" + id + "/payment-intent"))).andExpect(status().isOk());
        Payment card = payment(id);
        assertThat(card.getMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(card.getProvider()).isEqualTo(gateway.name());
        assertThat(card.getStripePaymentId()).isEqualTo(gateway.intents().getLast().id());
    }

    // ---------------------------------------------------------------- confirming the initial cash

    @Test
    void anAdminConfirmsTheInitialCashAndTheBookingBecomesActive() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        confirm(admin, id, "initial", "250.00").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("UPCOMING"))
                .andExpect(jsonPath("$.payment.method").value("CASH"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.cardLast4").doesNotExist());

        Booking b = bookings.findById(id).orElseThrow();
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.RENTED);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getPaidAt()).isNotNull();
        assertThat(recorded(id)).singleElement().satisfies(t -> {
            assertThat(t.getKind()).isEqualTo(PaymentTransaction.Kind.INITIAL);
            assertThat(t.getStatus()).isEqualTo(PaymentTransaction.Status.SUCCESS);
            assertThat(t.getAmount()).isEqualByComparingTo("250.00");
            assertThat(t.getRecordedBy().getUserId()).isEqualTo(admin.getUserId());
            assertThat(t.getProviderRef()).isNull();
        });
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("Your cash payment of LKR 250.00 was confirmed."));
    }

    @Test
    void aSuperAdminCanConfirmCash() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        confirm(saveUser(Role.SUPER_ADMIN), id, "initial", "250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void aCustomerCannotConfirmCashNotEvenTheirOwn() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        confirm(customer, id, "initial", "250").andExpect(status().isForbidden());
        confirm(customer, id, "balance", "250").andExpect(status().isForbidden());
        confirm(customer, id, "refund", "250").andExpect(status().isForbidden());
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void confirmingCashNeedsAuthentication() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        for (String step : List.of("initial", "balance", "refund")) {
            mvc.perform(json(post("/api/admin/bookings/" + id + "/cash/" + step), amount("250")))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void aRepeatedConfirmationRecordsTheCashOnceAndActivatesOnce() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        confirm(admin, id, "initial", "250").andExpect(status().isOk());
        confirm(admin, id, "initial", "250").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        assertThat(recorded(id)).hasSize(1);
        assertThat(subjects(customer).stream().filter(s -> s.startsWith("Booking confirmed"))).hasSize(1);
    }

    @Test
    void anAmountOtherThanTheAmountDueIsRefused() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        confirm(admin, id, "initial", "200").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("The amount due is LKR 250.00")));
        confirm(admin, id, "initial", "0").andExpect(status().isBadRequest());
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void cashCannotBeConfirmedUnlessTheCustomerChoseCash() throws Exception {
        UUID id = signedBooking();

        confirm(admin, id, "initial", "250").andExpect(status().isConflict());
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }

    @Test
    void cashIsNeverAProviderPayment() throws Exception {
        UUID id = cashRentalOf8000();
        end(id).andExpect(status().isOk());
        confirm(admin, id, "balance", "7750").andExpect(status().isOk());

        Payment p = payment(id);
        assertThat(p.getProvider()).isNull();
        assertThat(p.getStripePaymentId()).isNull();
        assertThat(p.getPaymentMethodRef()).isNull();
        assertThat(p.getBalanceIntentId()).isNull();
        assertThat(p.getCardLast4()).isNull();
        assertThat(recorded(id)).allSatisfy(t -> {
            assertThat(t.getProviderRef()).isNull();
            assertThat(t.getIntentRef()).isNull();
        });
        assertNoProviderCalls();
        mvc.perform(as(admin, get("/api/bookings/" + id + "/payment")))
                .andExpect(jsonPath("$.method").value("CASH"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.stripePaymentId").doesNotExist())
                .andExpect(jsonPath("$.transactions[0].recordedBy").value(admin.getFullName()));
    }

    @Test
    void aCancelledCashBookingCannotTakeCashAndItsExpiryNeedsNoProvider() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.FAILED);
        confirm(admin, id, "initial", "250").andExpect(status().isConflict());
        assertNoProviderCalls();
    }

    // ---------------------------------------------------------------- final cash settlement

    @Test
    void theFinalCashBalanceIsTheAdjustedTotalLessTheCashReceived() throws Exception {
        UUID id = cashRentalOf8000();
        review(report(id, true, "2000"), "RESOLVED", "1500");

        // Ending does not complete the rental: LKR 6,500 final total, 250 received, 6,250 due at the counter.
        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.balanceDue").value(6250.00))
                .andExpect(jsonPath("$.payment.refundDue").doesNotExist());
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Cash balance due"));

        String invoices = mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].subtotal").value(8000.00))
                .andExpect(jsonPath("$[0].total").value(6500.00))
                .andExpect(jsonPath("$[0].paymentMethod").value("CASH"))
                .andExpect(jsonPath("$[0].cashReceived").value(250.00))
                .andExpect(jsonPath("$[0].balanceDue").value(6250.00))
                .andReturn().getResponse().getContentAsString();
        assertThat(lastLineAmount(invoices)).isEqualByComparingTo("-1500.00");

        confirm(admin, id, "balance", "6250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist());
        Payment p = payment(id);
        assertThat(p.getAmount()).isEqualByComparingTo("6500.00");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recorded(id)).extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.INITIAL, PaymentTransaction.Kind.BALANCE);
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Final cash payment received"));
        assertThat(subjects(admin)).anyMatch(s -> s.startsWith("Final cash payment received"));
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].cashReceived").value(6500.00))
                .andExpect(jsonPath("$[0].balanceDue").doesNotExist());

        // Repeating the confirmation changes nothing.
        confirm(admin, id, "balance", "6250").andExpect(status().isOk());
        assertThat(recorded(id)).hasSize(2);
        assertNoProviderCalls();
    }

    @Test
    void theClaimedAmountAloneDoesNotReduceTheCashBalance() throws Exception {
        UUID id = cashRentalOf8000();
        String repair = report(id, true, "2000");
        end(id).andExpect(status().isConflict()); // the report must be resolved first

        // Resolved with no approved amount: the claim alone takes nothing off.
        review(repair, "RESOLVED", "null");
        end(id).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));
    }

    @Test
    void anApprovalStillUnderReviewBlocksTheFinalBill() throws Exception {
        UUID id = cashRentalOf8000();
        review(report(id, true, "2000"), "UNDER_REVIEW", "1500");

        end(id).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This rental has unresolved repair reports. Resolve them before calculating the final bill."));
        Booking b = bookings.findById(id).orElseThrow();
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(b.getEndedAt()).isNull();
        assertThat(payment(id).getBalanceDue()).isNull();
    }

    @Test
    void severalApprovedReimbursementsReduceTheCashBalanceTogether() throws Exception {
        UUID id = cashRentalOf8000();
        review(report(id, true, "1200"), "RESOLVED", "1000");
        review(report(id, true, "700"), "RESOLVED", "500");
        String declined = report(id, true, "900");
        review(declined, "UNDER_REVIEW", "900");
        end(id).andExpect(status().isConflict());
        review(declined, "RESOLVED", "null"); // resolved without a reimbursement

        // 8,000 - 1,500 = 6,500 total; 6,250 due after the 250 received.
        end(id).andExpect(jsonPath("$.pendingTotalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.balanceDue").value(6250.00));
    }

    @Test
    void cashAboveTheAdjustedTotalLeavesARefundDueThatOnlyAnAdminCanConfirm() throws Exception {
        // LKR 2,500 an hour: 2,500 cash at booking. 90 minutes = 2 hours = 5,000, less 3,000 approved = 2,000.
        UUID id = cashRentalUnderWay(saveScooter("20000.00", "2500.00"), "2500", 90);
        review(report(id, true, "3000"), "RESOLVED", "3000");

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(2000.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist())
                .andExpect(jsonPath("$.payment.refundDue").value(500.00));
        // Not handed back yet: nothing is recorded as refunded, and no provider is asked to refund.
        assertThat(recorded(id)).extracting(PaymentTransaction::getKind).containsExactly(PaymentTransaction.Kind.INITIAL);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Cash refund due"));
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].total").value(2000.00))
                .andExpect(jsonPath("$[0].cashReceived").value(2500.00))
                .andExpect(jsonPath("$[0].refundDue").value(500.00))
                .andExpect(jsonPath("$[0].cashRefunded").value(0.00));

        confirm(customer, id, "refund", "500").andExpect(status().isForbidden());
        confirm(admin, id, "refund", "400").andExpect(status().isConflict());

        confirm(admin, id, "refund", "500").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(2000.00))
                .andExpect(jsonPath("$.payment.refundDue").doesNotExist());
        Payment p = payment(id);
        assertThat(p.getAmount()).isEqualByComparingTo("2000.00");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(recorded(id).getLast()).satisfies(t -> {
            assertThat(t.getKind()).isEqualTo(PaymentTransaction.Kind.REFUND);
            assertThat(t.getAmount()).isEqualByComparingTo("500.00");
            assertThat(t.getRecordedBy().getUserId()).isEqualTo(admin.getUserId());
            assertThat(t.getProviderRef()).isNull();
        });
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("A cash refund of LKR 500.00 was recorded"));
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].cashReceived").value(2500.00))
                .andExpect(jsonPath("$[0].cashRefunded").value(500.00))
                .andExpect(jsonPath("$[0].refundDue").doesNotExist());

        confirm(admin, id, "refund", "500").andExpect(status().isOk());
        assertThat(recorded(id)).hasSize(2);
        assertNoProviderCalls();
    }

    @Test
    void aZeroFinalTotalOwesBackAllTheCashAndNeverChargesANegativeAmount() throws Exception {
        UUID id = cashRentalUnderWay(saveScooter("2500.00", "250.00"), "250", 30); // 1 hour: 250 subtotal
        review(report(id, true, "2000"), "RESOLVED", "2000");

        end(id).andExpect(jsonPath("$.pendingTotalCost").value(0.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist())
                .andExpect(jsonPath("$.payment.refundDue").value(250.00));

        confirm(admin, id, "refund", "250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(0.00));
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(p.getAmount()).isEqualByComparingTo("250.00");
        assertNoProviderCalls();
    }

    @Test
    void cashMatchingTheFinalTotalCompletesTheRentalAtOnce() throws Exception {
        UUID id = cashRentalUnderWay(saveScooter("2500.00", "250.00"), "250", 30);

        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(250.00));
        assertNoProviderCalls();
    }

    @Test
    void aCashBalanceCannotBePaidOnlineOrConfirmedBeforeTheRentalEnds() throws Exception {
        UUID id = cashRentalOf8000();
        confirm(admin, id, "balance", "7750").andExpect(status().isConflict());
        confirm(admin, id, "refund", "250").andExpect(status().isConflict());

        end(id).andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + id + "/balance-intent"))).andExpect(status().isConflict());
        assertNoProviderCalls();
    }

    @Test
    void aCancelledCashBookingOwesTheCashBackUntilAnAdminRecordsTheRefund() throws Exception {
        UUID id = signedBooking();
        chooseCash(id).andExpect(status().isOk());
        confirm(admin, id, "initial", "250").andExpect(status().isOk());

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.refundDue").value(250.00));
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(messages(customer)).anyMatch(m -> m.contains("A cash refund of LKR 250.00 is due to you"));

        confirm(admin, id, "refund", "250").andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("REFUNDED"));
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertNoProviderCalls();
    }

    @Test
    void theCsvReportsCashAsCash() throws Exception {
        UUID id = cashRentalOf8000();
        review(report(id, true, "2000"), "RESOLVED", "1500");
        end(id).andExpect(status().isOk());
        em.flush();

        String csv = reports.bookingsCsv(LocalDate.now(), LocalDate.now());
        List<String> header = cells(csv.lines().findFirst().orElseThrow());
        List<String> row = cells(csv.lines()
                .filter(l -> l.startsWith("\"" + bookings.findById(id).orElseThrow().getReference() + "\""))
                .findFirst().orElseThrow());
        assertThat(row.get(header.indexOf("Payment Method"))).isEqualTo("CASH");
        assertThat(row.get(header.indexOf("Total Amount (LKR)"))).isEqualTo("6500.00");
        assertThat(row.get(header.indexOf("Amount Paid (LKR)"))).isEqualTo("250.00");
        assertThat(row.get(header.indexOf("Balance Due (LKR)"))).isEqualTo("6250.00");
        assertThat(row.get(header.indexOf("Refund Due (LKR)"))).isEmpty();
        assertThat(csv).doesNotContainIgnoringCase("stripe");
    }

    private static BigDecimal lastLineAmount(String invoicesJson) {
        List<Object> lines = JsonPath.read(invoicesJson, "$[0].lines");
        return new BigDecimal(String.valueOf(JsonPath.<Object>read(lines.getLast(), "$.amount")));
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
