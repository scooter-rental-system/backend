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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Whole workflows in the order a customer and an admin use them, through the real controllers: payment, rental,
 * repair review, final bill, settlement. Above all, the final bill is never calculated while a self-arranged repair
 * report is unresolved, so an approved reimbursement can never be missed or changed afterwards. The scooter costs
 * LKR 250 an hour (2,500 a day): one hour is a subtotal of 250, which is also the amount paid at booking.
 */
@AutoConfigureMockMvc
@Transactional
class WorkflowAuditTests extends PostgresIntegrationTest {

    private static final String BLOCKED =
            "This rental has unresolved repair reports. Resolve them before calculating the final bill.";

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

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("audit." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Audit Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("aud" + id));
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

    private UUID signedBooking() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        Scooter s = new Scooter();
        s.setCode("AU-" + id);
        s.setModel("Audit Dio");
        s.setPlateNumber("AUP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s = scooters.saveAndFlush(s);
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                        .formatted(s.getScooterId(), LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/contract/sign")),
                "{\"signedName\": \"%s\", \"agree\": true}".formatted(customer.getFullName()))).andExpect(status().isOk());
        return bookingId;
    }

    private ResultActions cash(User who, UUID id, String step, String amount) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/bookings/" + id + "/cash/" + step)),
                "{\"amount\": " + amount + "}"));
    }

    private ResultActions submit(UUID id, String method) throws Exception {
        var request = multipart("/api/bookings/" + id + "/receipts")
                .file(new MockMultipartFile("receipt", "receipt.png", "image/png", RepairPhotoTests.PNG));
        if (method != null) {
            request.param("method", method);
        }
        return mvc.perform(as(customer, request));
    }

    private ResultActions approve(UUID receiptId, String amount) throws Exception {
        return mvc.perform(json(as(admin, post("/api/admin/payment-receipts/" + receiptId + "/approve")),
                "{\"amount\": " + amount + "}"));
    }

    private UUID pendingReceipt(UUID id) {
        em.flush();
        return receipts.findPendingByBookingId(id).orElseThrow().getPaymentReceiptId();
    }

    /** The rental started {@code minutesAgo} minutes ago (it was paid for a later start). */
    private void startedMinutesAgo(UUID id, long minutesAgo) {
        Booking b = bookings.findById(id).orElseThrow();
        b.setStartTime(LocalDateTime.now().minusMinutes(minutesAgo));
        bookings.saveAndFlush(b);
    }

    /** The initial LKR 250 paid by QR payment and approved, so the booking is ACTIVE and unstarted. */
    private UUID paidByQr() throws Exception {
        UUID id = signedBooking();
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")), "{\"method\": \"QR_TRANSFER\"}"))
                .andExpect(status().isOk());
        submit(id, null).andExpect(status().isOk());
        approve(pendingReceipt(id), "250").andExpect(status().isOk());
        return id;
    }

    /** As {@link #paidByQr}; the rental started {@code minutesAgo} minutes ago. */
    private UUID qrRental(long minutesAgo) throws Exception {
        UUID id = paidByQr();
        startedMinutesAgo(id, minutesAgo);
        return id;
    }

    /**
     * A rental paid in cash at the counter before cash left the checkout (Payment.method CASH), written directly
     * since no endpoint creates such a payment any more.
     */
    private UUID earlierCashRental(long minutesAgo) throws Exception {
        UUID id = signedBooking();
        Booking b = bookings.findById(id).orElseThrow();
        Payment p = new Payment();
        p.setBooking(b);
        p.setMethod(PaymentMethod.CASH);
        p.setAmount(new BigDecimal("250.00"));
        p.setStatus(PaymentStatus.SUCCESS);
        p.setPaidAt(LocalDateTime.now().minusDays(1));
        payments.save(p);
        PaymentTransaction t = new PaymentTransaction();
        t.setPayment(p);
        t.setKind(PaymentTransaction.Kind.INITIAL);
        t.setMethod(PaymentMethod.CASH);
        t.setAmount(new BigDecimal("250.00"));
        t.setStatus(PaymentTransaction.Status.SUCCESS);
        t.setRecordedBy(admin);
        t.setCreatedAt(LocalDateTime.now().minusDays(1));
        transactions.save(t);
        b.setStatus(BookingStatus.ACTIVE);
        b.getScooter().setStatus(ScooterStatus.RENTED);
        b.setStartTime(LocalDateTime.now().minusMinutes(minutesAgo));
        bookings.saveAndFlush(b);
        return id;
    }

    private String report(UUID id, boolean selfArranged, String claimed) throws Exception {
        String response = mvc.perform(json(as(customer, post("/api/bookings/" + id + "/repairs")), """
                        {"description": "Puncture repaired at a roadside shop", "selfArranged": %s, "claimedAmount": %s}"""
                        .formatted(selfArranged, claimed)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private ResultActions review(String reportId, String status, String approved) throws Exception {
        return mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                "{\"status\": \"%s\", \"adminNote\": null, \"approvedAmount\": %s}".formatted(status, approved)));
    }

    private ResultActions end(User who, UUID id) throws Exception {
        return mvc.perform(as(who, put("/api/bookings/" + id + "/complete")));
    }

    private ResultActions location(User who, UUID id) throws Exception {
        return mvc.perform(json(as(who, post("/api/bookings/" + id + "/location")),
                "{\"latitude\": 7.2906, \"longitude\": 80.6337, \"speedKmh\": 20}"));
    }

    private Booking booking(UUID id) {
        em.flush();
        em.clear();
        return bookings.findById(id).orElseThrow();
    }

    private Payment payment(UUID id) {
        em.flush();
        em.clear();
        return payments.findByBooking_BookingId(id).orElseThrow();
    }

    private List<PaymentTransaction> recorded(UUID id) {
        return transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(payment(id).getPaymentId());
    }

    private long subjects(User user, String prefix) {
        em.flush();
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(),
                Notification.Channel.IN_APP).stream().filter(n -> n.getSubject().startsWith(prefix)).count();
    }

    // ---------------------------------------------------------------- the reported manual issue

    @Test
    void anUnresolvedRepairWithAnApprovedAmountBlocksTheFinalBillForTheAdminAndTheCustomer() throws Exception {
        UUID id = qrRental(30);
        String repair = report(id, true, "150");
        review(repair, "REPORTED", "100").andExpect(status().isOk());

        end(admin, id).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(BLOCKED));
        end(customer, id).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(BLOCKED));
        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(b.getEndedAt()).isNull();
        assertThat(b.getPendingTotalCost()).isNull();
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices"))).andExpect(jsonPath("$").isEmpty());

        // Resolved and saved: the final bill now includes the approved 100, never the claimed 150.
        review(repair, "RESOLVED", "100").andExpect(status().isOk());
        end(admin, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingTotalCost").value(150.00))
                .andExpect(jsonPath("$.payment.refundDue").value(100.00));
    }

    @Test
    void aRepairTheCustomerDidNotArrangeThemselvesDoesNotBlockTheFinalBill() throws Exception {
        UUID id = qrRental(30);
        report(id, false, "null"); // assistance only: no reimbursement can be approved on it

        end(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    // ---------------------------------------------------------------- cash

    @Test
    void cashOverpaymentAfterTheApprovedReimbursementLeavesARefundDueUntilTheAdminHandsItBack() throws Exception {
        UUID id = earlierCashRental(30);
        review(report(id, true, "150"), "RESOLVED", "100").andExpect(status().isOk());

        end(admin, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(150.00))
                .andExpect(jsonPath("$.payment.refundDue").value(100.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist());
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].subtotal").value(250.00))
                .andExpect(jsonPath("$[0].total").value(150.00))
                .andExpect(jsonPath("$[0].paymentMethod").value("CASH"))
                .andExpect(jsonPath("$[0].cashReceived").value(250.00))
                .andExpect(jsonPath("$[0].refundDue").value(100.00));
        assertThat(subjects(customer, "Cash refund due")).isEqualTo(1);
        assertThat(subjects(customer, "Cash refund recorded")).isZero();

        cash(customer, id, "refund", "100").andExpect(status().isForbidden());
        cash(admin, id, "refund", "100").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        cash(admin, id, "refund", "100").andExpect(status().isOk()); // a second press changes nothing

        assertThat(recorded(id)).extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.INITIAL, PaymentTransaction.Kind.REFUND);
        Payment p = payment(id);
        assertThat(p.getAmount()).isEqualByComparingTo("150.00");
        assertThat(p.getRefundDue()).isNull();
        Booking b = booking(id);
        assertThat(b.getTotalCost()).isEqualByComparingTo("150.00");
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(subjects(customer, "Cash refund recorded")).isEqualTo(1);
        assertThat(subjects(customer, "Rental completed")).isEqualTo(1);
    }

    @Test
    void aCompletedRentalCannotBeFinalisedOrSettledAgain() throws Exception {
        UUID id = qrRental(90); // 2 hours: 500, less 250 received = 250 due
        end(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(250.00));
        cash(admin, id, "balance", "250").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));

        end(admin, id).andExpect(status().isConflict());
        cash(admin, id, "balance", "250").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        cash(admin, id, "refund", "250").andExpect(status().isConflict());
        assertThat(recorded(id)).hasSize(2);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("500.00");
        // A repair can no longer be reported, so nothing can reach the closed bill.
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/repairs")),
                "{\"description\": \"Late\", \"selfArranged\": true, \"claimedAmount\": 100}"))
                .andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- transfers

    @Test
    void aFinalReceiptWaitingForVerificationKeepsTheRentalOpen() throws Exception {
        UUID id = signedBooking();
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")), "{\"method\": \"QR_TRANSFER\"}"))
                .andExpect(status().isOk());
        submit(id, null).andExpect(status().isOk());
        assertThat(booking(id).getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE); // not yet approved
        approve(pendingReceipt(id), "250").andExpect(status().isOk());
        assertThat(booking(id).getScooter().getStatus()).isEqualTo(ScooterStatus.RENTED);
        startedMinutesAgo(id, 90);
        String repair = report(id, true, "150");
        end(customer, id).andExpect(status().isConflict());
        review(repair, "RESOLVED", "100").andExpect(status().isOk());

        end(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(150.00));
        submit(id, "BANK_TRANSFER").andExpect(status().isOk());

        end(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        cash(admin, id, "balance", "150").andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(payment(id).getBalanceDue()).isEqualByComparingTo("150.00");
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices")))
                .andExpect(jsonPath("$[0].balanceDue").value(150.00));

        approve(pendingReceipt(id), "150").andExpect(status().isOk());
        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(b.getTotalCost()).isEqualByComparingTo("400.00");
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("400.00");
    }

    @Test
    void aCancelledBookingCannotBePaidOrActivated() throws Exception {
        UUID byTransfer = signedBooking();
        mvc.perform(json(as(customer, post("/api/bookings/" + byTransfer + "/transfer")),
                "{\"method\": \"BANK_TRANSFER\"}")).andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + byTransfer + "/cancel"))).andExpect(status().isOk());
        submit(byTransfer, null).andExpect(status().isConflict());
        mvc.perform(json(as(customer, post("/api/bookings/" + byTransfer + "/transfer")),
                "{\"method\": \"QR_TRANSFER\"}")).andExpect(status().isConflict());
        cash(admin, byTransfer, "balance", "250").andExpect(status().isConflict());

        Booking b = booking(byTransfer);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(payment(byTransfer).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(recorded(byTransfer)).isEmpty();
    }

    // ---------------------------------------------------------------- GPS

    @Test
    void theRiderReportsLocationOnlyDuringTheirOwnPaidRentalInProgress() throws Exception {
        UUID id = signedBooking();
        mvc.perform(json(as(customer, post("/api/bookings/" + id + "/transfer")), "{\"method\": \"QR_TRANSFER\"}"))
                .andExpect(status().isOk());
        submit(id, null).andExpect(status().isOk());
        location(customer, id).andExpect(status().isConflict()); // not paid yet: the receipt is not approved

        approve(pendingReceipt(id), "250").andExpect(status().isOk());
        startedMinutesAgo(id, 30);
        location(customer, id).andExpect(status().isAccepted());
        location(saveUser(Role.USER), id).andExpect(status().isNotFound());
        mvc.perform(as(admin, get("/api/admin/gps/bookings/" + id + "/history"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(1));

        end(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
        location(customer, id).andExpect(status().isConflict());
    }
}
