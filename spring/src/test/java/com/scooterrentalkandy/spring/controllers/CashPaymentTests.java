package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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
import com.scooterrentalkandy.spring.repository.PaymentReceiptRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.ReportService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.Rentals;
import com.scooterrentalkandy.spring.support.TestNics;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
import jakarta.persistence.EntityManager;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cash at the rental counter (BE-044), with a card provider configured (the test gateway) to show cash never reaches
 * it. Cash is not a checkout option: the customer pays the initial charge online (here by QR payment or bank
 * transfer, approved by an admin), and only an admin records cash, when the customer hands over the final balance at
 * the counter. The default scooter (LKR 2,500 a day, 250 an hour) takes LKR 250 at booking; a rental 73.5 hours in
 * bills 3 days + 2 hours = 8,000. The final balance is the adjusted final total (after any approved RESOLVED repair
 * reimbursement) less what was received. Bookings paid in cash before cash left the checkout are still settled and
 * refunded at the counter.
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
    private PaymentReceiptRepository receipts;

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
    void setUp() throws Exception {
        gateway.reset();
        customer = saveUser(Role.USER);
        admin = saveUser(Role.ADMIN);
        mvc.perform(as(admin, multipart("/api/admin/payment-settings/qr-image")
                .file(new MockMultipartFile("image", "qr.png", "image/png", RepairPhotoTests.PNG))))
                .andExpect(status().isOk());
        mvc.perform(json(as(admin, put("/api/admin/payment-settings")), """
                {"qrEnabled": true, "qrDisplayName": "Scooter Rent Kandy", "bankEnabled": true,
                 "bankName": "Bank of Ceylon", "bankAccountHolder": "Scooter Rent Kandy", "bankAccountNumber": "0001234567",
                 "bankBranch": "Kandy"}""")).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- fixtures

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

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(User user, B request) {
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

    private ResultActions confirm(User who, UUID bookingId, String step, String value) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/bookings/" + bookingId + "/cash/" + step)), amount(value)));
    }

    private ResultActions submit(UUID bookingId, String method) throws Exception {
        var request = multipart("/api/bookings/" + bookingId + "/receipts")
                .file(new MockMultipartFile("receipt", "receipt.png", "image/png", RepairPhotoTests.PNG))
                .param("reference", "TXN-" + unique());
        if (method != null) {
            request.param("method", method);
        }
        return mvc.perform(as(customer, request));
    }

    private ResultActions approve(UUID bookingId, String value) throws Exception {
        em.flush();
        UUID receiptId = receipts.findPendingByBookingId(bookingId).orElseThrow().getPaymentReceiptId();
        return mvc.perform(json(as(admin, post("/api/admin/payment-receipts/" + receiptId + "/approve")),
                amount(value)));
    }

    /** The initial charge paid by {@code method} and approved by the admin, without starting the rental clock. */
    private UUID paidOnline(Scooter s, PaymentMethod method, String initial) throws Exception {
        UUID id = signedBooking(s);
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")), "{\"method\": \"" + method + "\"}"))
                .andExpect(status().isOk());
        submit(id, null).andExpect(status().isOk());
        approve(id, initial).andExpect(status().isOk());
        return id;
    }

    /** As {@link #paidOnline}; the rental started {@code minutesAgo} minutes ago. */
    private UUID rentalUnderWay(Scooter s, PaymentMethod method, String initial, long minutesAgo) throws Exception {
        UUID id = paidOnline(s, method, initial);
        startedMinutesAgo(id, minutesAgo);
        return id;
    }

    /** 73.5 hours in: 3 days + 2 extra hours = LKR 8,000 subtotal, LKR 250 received by {@code method}. */
    private UUID rentalOf8000(PaymentMethod method) throws Exception {
        return rentalUnderWay(saveScooter("2500.00", "250.00"), method, "250", 73 * 60 + 30);
    }

    /** Handed over by the admin at an odometer of 1000.0 km and started by the customer {@code minutesAgo} ago. */
    private void startedMinutesAgo(UUID id, long minutesAgo) {
        Booking b = bookings.findById(id).orElseThrow();
        Rentals.started(b, LocalDateTime.now().minusMinutes(minutesAgo), admin, Rentals.START_ODOMETER_KM);
        b.getScooter().setStatus(ScooterStatus.RENTED);
        bookings.saveAndFlush(b);
    }

    /**
     * A booking paid in cash at the counter before cash left the checkout: its payment is CASH, recorded by an admin,
     * and the rental is under way. Written directly, since no endpoint creates such a payment any more.
     */
    private UUID earlierCashRental(Scooter s, String initial, long minutesAgo) throws Exception {
        UUID id = signedBooking(s);
        Booking b = bookings.findById(id).orElseThrow();
        Payment p = new Payment();
        p.setBooking(b);
        p.setMethod(PaymentMethod.CASH);
        p.setAmount(new BigDecimal(initial));
        p.setStatus(PaymentStatus.SUCCESS);
        p.setPaidAt(LocalDateTime.now().minusDays(1));
        payments.save(p);
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(PaymentTransaction.Kind.INITIAL);
        t.setMethod(PaymentMethod.CASH);
        t.setAmount(new BigDecimal(initial));
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setRecordedBy(admin);
        t.setCreatedAt(LocalDateTime.now().minusDays(1));
        transactions.save(t);
        Rentals.started(b, LocalDateTime.now().minusMinutes(minutesAgo), admin, Rentals.START_ODOMETER_KM);
        b.getScooter().setStatus(ScooterStatus.RENTED);
        bookings.saveAndFlush(b);
        return id;
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

    /** Ends the rental; an admin enters the ending odometer, unchanged since the handover (no excess distance). */
    private ResultActions end(User who, UUID bookingId) throws Exception {
        return mvc.perform(json(as(who, put("/api/bookings/" + bookingId + "/complete")),
                who.getRole() == Role.USER ? "{}" : "{\"endOdometerKm\": 1000.0}"));
    }

    private Booking booking(UUID bookingId) {
        em.flush();
        em.clear();
        return bookings.findById(bookingId).orElseThrow();
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

    // ---------------------------------------------------------------- cash is not a checkout option

    @Test
    void theCustomerCannotChooseOrRecordCashAtCheckout() throws Exception {
        UUID id = signedBooking();

        // Neither the earlier "pay cash at the counter" choice nor an admin "initial cash" step exists any more.
        mvc.perform(as(customer, post("/api/bookings/" + id + "/cash"))).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/bookings/" + id + "/cash")).andExpect(status().isUnauthorized());
        confirm(admin, id, "initial", "250").andExpect(status().is4xxClientError());
        // Cash is not a transfer method either.
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")), "{\"method\": \"CASH\"}"))
                .andExpect(status().is4xxClientError());
        submit(id, "CASH").andExpect(status().is4xxClientError());

        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertNoProviderCalls();
    }

    @Test
    void onlyAnAdminRecordsCash() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        end(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));

        confirm(customer, id, "balance", "7750").andExpect(status().isForbidden());
        confirm(customer, id, "refund", "7750").andExpect(status().isForbidden());
        for (String step : List.of("balance", "refund")) {
            mvc.perform(json(post("/api/admin/bookings/" + id + "/cash/" + step), amount("7750")))
                    .andExpect(status().isUnauthorized());
        }
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(payment(id).getBalanceDue()).isEqualByComparingTo("7750.00");

        confirm(saveUser(Role.SUPER_ADMIN), id, "balance", "7750").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // ---------------------------------------------------------------- initial online payment

    @Test
    void approvingTheInitialQrOrBankPaymentMakesTheBookingReadyForPickupAndNeverCompletesIt() throws Exception {
        for (PaymentMethod method : List.of(PaymentMethod.QR_TRANSFER, PaymentMethod.BANK_TRANSFER)) {
            UUID id = signedBooking();
            mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")),
                    "{\"method\": \"" + method + "\"}")).andExpect(status().isOk());
            submit(id, null).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PENDING"));
            assertThat(booking(id).getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);

            approve(id, "250").andExpect(status().isOk());

            Booking b = booking(id);
            assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
            assertThat(b.getActualStartTime()).isNull();
            assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
            assertThat(b.getEndedAt()).isNull();
            assertThat(b.getEndTime()).isNull();
            assertThat(b.getTotalCost()).isNull();
            assertThat(payment(id).getBalanceDue()).isNull();
            // Nothing is due at the counter while the rental runs.
            confirm(admin, id, "balance", "250").andExpect(status().isConflict());
        }
    }

    // ---------------------------------------------------------------- final cash at the counter

    @Test
    void anInitialQrPaymentAndTheFinalBalanceInCashSettleTheRental() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        review(report(id, true, "2000"), "RESOLVED", "1500");

        // Ending does not complete the rental: LKR 6,500 final total, 250 received, 6,250 due.
        end(admin, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.balanceDue").value(6250.00))
                .andExpect(jsonPath("$.payment.refundDue").doesNotExist());
        assertThat(messages(customer)).anyMatch(m -> m.contains("or pay it in cash at the rental counter"));

        // The amount recorded is the server's balance: a different amount (a stale screen) is refused.
        confirm(admin, id, "balance", "6500").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("The amount due is LKR 6250.00")));
        confirm(admin, id, "balance", "0").andExpect(status().isBadRequest());
        assertThat(recorded(id)).hasSize(1);

        confirm(admin, id, "balance", "6250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.stage").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.method").value("QR_TRANSFER"))
                .andExpect(jsonPath("$.payment.status").value("SUCCESS"))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist());

        // The QR payment keeps its method; the cash is its own transaction.
        List<PaymentTransaction> recorded = recorded(id);
        assertThat(recorded).extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.INITIAL, PaymentTransaction.Kind.BALANCE);
        assertThat(recorded).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.QR_TRANSFER, PaymentMethod.CASH);
        assertThat(recorded.get(1).getAmount()).isEqualByComparingTo("6250.00");
        assertThat(recorded.get(1).getRecordedBy().getUserId()).isEqualTo(admin.getUserId());
        assertThat(recorded.get(1).getProviderRef()).isNull();
        Payment p = payment(id);
        assertThat(p.getAmount()).isEqualByComparingTo("6500.00");
        assertThat(p.getMethod()).isEqualTo(PaymentMethod.QR_TRANSFER);
        Booking b = booking(id);
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);

        // Both sides see it completed, with the whole payment history.
        for (User who : List.of(customer, admin)) {
            mvc.perform(as(who, get("/api/bookings/" + id))).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.payment.status").value("SUCCESS"));
            mvc.perform(as(who, get("/api/bookings/" + id + "/invoices"))).andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].subtotal").value(8000.00))
                    .andExpect(jsonPath("$[0].total").value(6500.00))
                    .andExpect(jsonPath("$[0].paymentMethod").value("QR_TRANSFER"))
                    .andExpect(jsonPath("$[0].paymentMethods[0]").value("QR_TRANSFER"))
                    .andExpect(jsonPath("$[0].paymentMethods[1]").value("CASH"))
                    .andExpect(jsonPath("$[0].amountReceived").value(6500.00))
                    .andExpect(jsonPath("$[0].balanceDue").doesNotExist());
        }
        mvc.perform(as(admin, get("/api/bookings/" + id + "/payment")))
                .andExpect(jsonPath("$.transactions[0].method").value("QR_TRANSFER"))
                .andExpect(jsonPath("$.transactions[0].amount").value(250.00))
                .andExpect(jsonPath("$.transactions[1].method").value("CASH"))
                .andExpect(jsonPath("$.transactions[1].amount").value(6250.00));
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Final cash payment received"));
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Rental completed"));
        assertThat(subjects(admin)).anyMatch(s -> s.startsWith("Final cash payment received"));
        assertNoProviderCalls();
    }

    @Test
    void anInitialBankTransferAndTheFinalBalanceInCashSettleTheRental() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.BANK_TRANSFER);
        review(report(id, true, "2000"), "RESOLVED", "1500");
        end(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(6250.00));

        confirm(admin, id, "balance", "6250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(6500.00));

        assertThat(recorded(id)).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.BANK_TRANSFER, PaymentMethod.CASH);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("6500.00");
        assertThat(booking(id).getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void aRepeatedCashConfirmationRecordsTheCashOnceAndCompletesOnce() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        end(admin, id).andExpect(status().isOk());

        confirm(admin, id, "balance", "7750").andExpect(status().isOk());
        confirm(admin, id, "balance", "7750").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(recorded(id)).hasSize(2);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("8000.00");
        assertThat(subjects(customer).stream().filter(s -> s.startsWith("Final cash payment received"))).hasSize(1);
        assertThat(subjects(customer).stream().filter(s -> s.startsWith("Rental completed"))).hasSize(1);
    }

    @Test
    void cashCannotBeRecordedBeforeTheFinalBillOrWhileARepairIsUnresolved() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        confirm(admin, id, "balance", "7750").andExpect(status().isConflict()); // no final bill yet

        String repair = report(id, true, "2000");
        review(repair, "UNDER_REVIEW", "1500");
        end(admin, id).andExpect(status().isConflict()).andExpect(jsonPath("$.message")
                .value("This rental has unresolved repair reports. Resolve them before calculating the final bill."));
        confirm(admin, id, "balance", "7750").andExpect(status().isConflict());
        assertThat(booking(id).getEndedAt()).isNull();

        // Resolved and saved, the approved amount reduces the final bill; only then is cash due.
        review(repair, "RESOLVED", "1500");
        end(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(6250.00));
        confirm(admin, id, "balance", "6250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void cashIsNotDueWhenARefundIsDue() throws Exception {
        // One hour in: 250 billed, less an approved reimbursement of 100 = 150 against 250 received.
        UUID id = rentalUnderWay(saveScooter("2500.00", "250.00"), PaymentMethod.QR_TRANSFER, "250", 30);
        review(report(id, true, "100"), "RESOLVED", "100");
        end(admin, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.refundDue").value(100.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist());

        confirm(admin, id, "balance", "100").andExpect(status().isConflict());
        // A QR overpayment is refunded from the business account and recorded as a manual refund, not as cash.
        confirm(admin, id, "refund", "100").andExpect(status().isConflict());
        assertThat(recorded(id)).hasSize(1);
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }

    @Test
    void cashIsNotDueWhenTheReceivedAmountMatchesTheFinalTotal() throws Exception {
        UUID id = rentalUnderWay(saveScooter("2500.00", "250.00"), PaymentMethod.BANK_TRANSFER, "250", 30);

        end(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(250.00));
        confirm(admin, id, "balance", "0.01").andExpect(status().isConflict());
        assertThat(recorded(id)).hasSize(1);
    }

    @Test
    void cashIsNotRecordedAfterTheFinalTransferWasApproved() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        end(admin, id).andExpect(status().isOk());
        submit(id, "QR_TRANSFER").andExpect(status().isOk());

        // The customer may already have transferred the money: no cash while the receipt waits.
        confirm(admin, id, "balance", "7750").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.containsString("waiting for verification")));
        approve(id, "7750").andExpect(status().isOk());

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.COMPLETED);
        // Completed by the QR payment: no cash is recorded on top of it.
        confirm(admin, id, "balance", "7750").andExpect(status().isConflict());
        assertThat(recorded(id)).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.QR_TRANSFER, PaymentMethod.QR_TRANSFER);
    }

    @Test
    void cashCannotSettleACancelledBooking() throws Exception {
        UUID id = signedBooking();
        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        confirm(admin, id, "balance", "250").andExpect(status().isConflict());
        confirm(admin, id, "refund", "250").andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    @Test
    void theCsvListsEveryPaymentMethodUsed() throws Exception {
        UUID mixed = rentalOf8000(PaymentMethod.QR_TRANSFER);
        review(report(mixed, true, "2000"), "RESOLVED", "1500");
        end(admin, mixed).andExpect(status().isOk());
        confirm(admin, mixed, "balance", "6250").andExpect(status().isOk());
        UUID open = rentalOf8000(PaymentMethod.BANK_TRANSFER);
        end(admin, open).andExpect(status().isOk());
        em.flush();

        String csv = reports.bookingsCsv(LocalDate.now(), LocalDate.now());
        List<String> header = cells(csv.lines().findFirst().orElseThrow());
        assertThat(header).contains("Payment Methods").doesNotContain("Payment Method");
        List<String> row = row(csv, mixed);
        assertThat(row.get(header.indexOf("Payment Methods"))).isEqualTo("QR_TRANSFER + CASH");
        assertThat(row.get(header.indexOf("Total Amount (LKR)"))).isEqualTo("6500.00");
        assertThat(row.get(header.indexOf("Amount Paid (LKR)"))).isEqualTo("6500.00");
        assertThat(row.get(header.indexOf("Balance Due (LKR)"))).isEmpty();
        assertThat(row.get(header.indexOf("Status"))).isEqualTo("COMPLETED");
        List<String> unpaid = row(csv, open);
        assertThat(unpaid.get(header.indexOf("Payment Methods"))).isEqualTo("BANK_TRANSFER");
        assertThat(unpaid.get(header.indexOf("Balance Due (LKR)"))).isEqualTo("7750.00");
        assertThat(csv).doesNotContainIgnoringCase("stripe");
    }

    // ---------------------------------------------------------------- bookings paid in cash earlier

    @Test
    void anEarlierCashBookingStillSettlesItsBalanceAtTheCounter() throws Exception {
        UUID id = earlierCashRental(saveScooter("2500.00", "250.00"), "250", 73 * 60 + 30);

        end(admin, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));
        assertThat(subjects(customer)).anyMatch(s -> s.startsWith("Cash balance due"));
        confirm(admin, id, "balance", "7750").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(recorded(id)).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.CASH, PaymentMethod.CASH);
        assertNoProviderCalls();
    }

    @Test
    void anEarlierCashOverpaymentIsHandedBackOnlyByAnAdmin() throws Exception {
        // LKR 2,500 an hour: 2,500 cash at booking. 90 minutes = 2 hours = 5,000, less 3,000 approved = 2,000.
        UUID id = earlierCashRental(saveScooter("20000.00", "2500.00"), "2500", 90);
        review(report(id, true, "3000"), "RESOLVED", "3000");

        end(admin, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(2000.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist())
                .andExpect(jsonPath("$.payment.refundDue").value(500.00));
        confirm(admin, id, "balance", "500").andExpect(status().isConflict()); // a refund is due, not a balance
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].cashReceived").value(2500.00))
                .andExpect(jsonPath("$[0].refundDue").value(500.00))
                .andExpect(jsonPath("$[0].cashRefunded").value(0.00));

        confirm(customer, id, "refund", "500").andExpect(status().isForbidden());
        confirm(admin, id, "refund", "400").andExpect(status().isConflict());
        confirm(admin, id, "refund", "500").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.totalCost").value(2000.00))
                .andExpect(jsonPath("$.payment.refundDue").doesNotExist());
        confirm(admin, id, "refund", "500").andExpect(status().isOk());

        assertThat(recorded(id)).extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.INITIAL, PaymentTransaction.Kind.REFUND);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("2000.00");
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("A cash refund of LKR 500.00 was recorded"));
        assertNoProviderCalls();
    }

    @Test
    void anEarlierCashBookingCancelledBeforeItStartedOwesTheCashBack() throws Exception {
        UUID id = earlierCashRental(saveScooter("2500.00", "250.00"), "250", 0);
        // Paid in cash, still waiting for pickup: not handed over, not started.
        Booking b = bookings.findById(id).orElseThrow();
        b.setStatus(BookingStatus.READY_FOR_PICKUP);
        b.setActualStartTime(null);
        b.setCustomerStartConfirmedAt(null);
        b.setHandoverConfirmedAt(null);
        b.setHandoverConfirmedBy(null);
        b.setStartOdometerKm(null);
        b.getScooter().setStatus(ScooterStatus.AVAILABLE);
        b.setStartTime(LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES));
        bookings.saveAndFlush(b);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.payment.refundDue").value(250.00));
        confirm(admin, id, "refund", "250").andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("REFUNDED"));
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertNoProviderCalls();
    }

    private static List<String> row(String csv, UUID bookingId) {
        return cells(csv.lines().filter(l -> l.contains("\"" + bookingId + "\"")).findFirst().orElseThrow());
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
