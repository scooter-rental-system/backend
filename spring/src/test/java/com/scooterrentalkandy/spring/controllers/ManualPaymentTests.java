package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentReceipt;
import com.scooterrentalkandy.spring.entity.PaymentSettings;
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
import com.scooterrentalkandy.spring.repository.PaymentSettingsRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
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
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * QR payments and bank transfers (BE-045), with a card provider configured (the test gateway) to show transfers never
 * reach it. The customer transfers outside the application and uploads a receipt; only an admin approves it, and only
 * approval counts the money as received. The default scooter (LKR 2,500 a day, 250 an hour) takes LKR 250 at booking;
 * a rental 73.5 hours in bills 3 days + 2 hours = 8,000. The final balance is the adjusted final total (after any
 * approved RESOLVED repair reimbursement) less what was received.
 */
@AutoConfigureMockMvc
@Transactional
class ManualPaymentTests extends PostgresIntegrationTest {

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
    private PaymentSettingsRepository settings;

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
        configure(true, true);
    }

    // ---------------------------------------------------------------- fixtures

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("transfer." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Transfer Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("trf" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    /** QR and bank transfer on or off, with their details set (the QR image through the admin upload). */
    private void configure(boolean qr, boolean bank) throws Exception {
        mvc.perform(as(admin, multipart("/api/admin/payment-settings/qr-image")
                .file(new MockMultipartFile("image", "qr.png", "image/png", RepairPhotoTests.PNG))))
                .andExpect(status().isOk());
        mvc.perform(json(as(admin, put("/api/admin/payment-settings")), """
                {"qrEnabled": %s, "qrDisplayName": "Scooter Rent Kandy", "bankEnabled": %s,
                 "bankName": "Bank of Ceylon", "bankAccountHolder": "Scooter Rent Kandy", "bankAccountNumber": "0001234567",
                 "bankBranch": "Kandy"}""".formatted(qr, bank))).andExpect(status().isOk());
    }

    private <B extends AbstractMockHttpServletRequestBuilder<B>> B as(User user, B request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("TR-" + id);
        s.setModel("Transfer Dio");
        s.setPlateNumber("TRP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    /** A booking with the agreement signed, awaiting payment. */
    private UUID signedBooking() throws Exception {
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                        .formatted(saveScooter().getScooterId(),
                                LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID bookingId = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/contract/sign")),
                "{\"signedName\": \"%s\", \"agree\": true}".formatted(customer.getFullName()))).andExpect(status().isOk());
        return bookingId;
    }

    private ResultActions choose(UUID bookingId, PaymentMethod method) throws Exception {
        return mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/transfer")),
                "{\"method\": \"" + method + "\"}"));
    }

    private static MockMultipartFile receipt(String name, String type, byte[] bytes) {
        return new MockMultipartFile("receipt", name, type, bytes);
    }

    private static MockMultipartFile png() {
        return receipt("receipt.png", "image/png", RepairPhotoTests.PNG);
    }

    private ResultActions submit(User who, UUID bookingId, MockMultipartFile file, String method, String reference)
            throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/bookings/" + bookingId + "/receipts");
        if (file != null) {
            request.file(file);
        }
        if (method != null) {
            request.param("method", method);
        }
        if (reference != null) {
            request.param("reference", reference);
        }
        return mvc.perform(as(who, request));
    }

    private ResultActions submit(UUID bookingId) throws Exception {
        return submit(customer, bookingId, png(), null, "TXN-778899");
    }

    private ResultActions approve(User who, UUID receiptId, String amount) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/payment-receipts/" + receiptId + "/approve")),
                "{\"amount\": " + amount + "}"));
    }

    private ResultActions reject(User who, UUID receiptId, String reason) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/payment-receipts/" + receiptId + "/reject")),
                "{\"reason\": \"" + reason + "\"}"));
    }

    private UUID pendingReceipt(UUID bookingId) {
        em.flush();
        return receipts.findPendingByBookingId(bookingId).orElseThrow().getPaymentReceiptId();
    }

    /**
     * The initial payment made by {@code method} and approved, the scooter handed over at an odometer of 1000.0 km and
     * the start confirmed by the customer; the rental then moved to have started {@code minutesAgo} minutes ago.
     */
    private UUID transferRentalUnderWay(PaymentMethod method, long minutesAgo) throws Exception {
        UUID id = signedBooking();
        choose(id, method).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        approve(admin, pendingReceipt(id), "250").andExpect(status().isOk());
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/handover")), "{\"startOdometerKm\": 1000.0}"))
                .andExpect(status().isOk());
        mvc.perform(as(customer, post("/api/bookings/" + id + "/start"))).andExpect(status().isOk());
        Booking b = bookings.findById(id).orElseThrow();
        b.setActualStartTime(LocalDateTime.now().minusMinutes(minutesAgo));
        b.setCustomerStartConfirmedAt(b.getActualStartTime());
        bookings.saveAndFlush(b);
        return id;
    }

    /** 73.5 hours in: 3 days + 2 extra hours = LKR 8,000 subtotal, LKR 250 received. */
    private UUID rentalOf8000(PaymentMethod method) throws Exception {
        return transferRentalUnderWay(method, 73 * 60 + 30);
    }

    private String report(UUID bookingId, boolean selfArranged, String claimed) throws Exception {
        String response = mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/repairs")), """
                        {"description": "Puncture repaired at a roadside shop", "selfArranged": %s, "claimedAmount": %s}"""
                        .formatted(selfArranged, claimed)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void review(String reportId, String status, String approved) throws Exception {
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                        "{\"status\": \"%s\", \"adminNote\": null, \"approvedAmount\": %s}".formatted(status, approved)))
                .andExpect(status().isOk());
    }

    /** An admin ends the rental; the odometer is unchanged since the handover (no excess distance). */
    private ResultActions end(UUID bookingId) throws Exception {
        return mvc.perform(json(as(admin, put("/api/bookings/" + bookingId + "/complete")), "{\"endOdometerKm\": 1000.0}"));
    }

    private Payment payment(UUID bookingId) {
        em.flush();
        em.clear();
        return payments.findByBooking_BookingId(bookingId).orElseThrow();
    }

    private Booking booking(UUID bookingId) {
        em.flush();
        em.clear();
        return bookings.findById(bookingId).orElseThrow();
    }

    private List<PaymentTransaction> recorded(UUID bookingId) {
        return transactions.findByPayment_PaymentIdOrderByCreatedAtAsc(payment(bookingId).getPaymentId());
    }

    private List<String> messages(User user) {
        em.flush();
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(user.getUserId(),
                Notification.Channel.IN_APP).stream().map(n -> n.getSubject() + " | " + n.getMessage()).toList();
    }

    private void assertNoProviderCalls() {
        assertThat(gateway.customers()).isEmpty();
        assertThat(gateway.intents()).isEmpty();
        assertThat(gateway.charges()).isEmpty();
        assertThat(gateway.refunds()).isEmpty();
    }

    // ---------------------------------------------------------------- choosing and submitting

    @Test
    void aReceiptWaitsForVerificationAndDoesNotActivateTheBooking() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.method").value("QR_TRANSFER"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.receipts").isEmpty());

        submit(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.payment.status").value("PENDING"))
                .andExpect(jsonPath("$.payment.paidAt").doesNotExist())
                .andExpect(jsonPath("$.payment.receipts[0].status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.payment.receipts[0].kind").value("INITIAL"))
                .andExpect(jsonPath("$.payment.receipts[0].method").value("QR_TRANSFER"))
                .andExpect(jsonPath("$.payment.receipts[0].amount").value(250.00))
                .andExpect(jsonPath("$.payment.receipts[0].transferReference").value("TXN-778899"))
                .andExpect(jsonPath("$.payment.receipts[0].submittedAt").exists())
                .andExpect(jsonPath("$.payment.receipts[0].storageKey").doesNotExist());

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment(id).getProvider()).isNull();
        assertThat(recorded(id)).isEmpty();
        assertThat(messages(admin)).anyMatch(m -> m.startsWith("Payment receipt to verify"));
        assertNoProviderCalls();
    }

    @Test
    void aReceiptImageIsRequiredForQrAndBankTransfer() throws Exception {
        UUID qr = signedBooking();
        choose(qr, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(customer, qr, null, null, "TXN-1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Attach a photo or screenshot of the payment receipt"));

        UUID bank = signedBooking();
        choose(bank, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());
        submit(customer, bank, null, null, null).andExpect(status().isBadRequest());

        em.flush();
        assertThat(receipts.findPendingByBookingId(qr)).isEmpty();
        assertThat(receipts.findPendingByBookingId(bank)).isEmpty();
    }

    @Test
    void onlyARealJpegPngOrWebpReceiptOfAtMost5MbIsAccepted() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());

        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>"
                .getBytes(StandardCharsets.UTF_8);
        byte[] html = "<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] pdf = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n".getBytes(StandardCharsets.ISO_8859_1);
        byte[] exe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00};
        for (MockMultipartFile bad : new MockMultipartFile[] {
                receipt("r.svg", "image/svg+xml", svg), receipt("r.png", "image/png", svg),
                receipt("r.html", "text/html", html), receipt("r.pdf", "application/pdf", pdf),
                receipt("r.exe", "application/octet-stream", exe), receipt("r.jpg", "image/jpeg", RepairPhotoTests.PNG)}) {
            submit(customer, id, bad, null, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("The receipt must be a JPEG, PNG or WebP image."));
        }
        submit(customer, id, receipt("r.png", "image/png", new byte[5 * 1024 * 1024 + 1]), null, null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The receipt must be 5 MB or smaller."));
        submit(customer, id, receipt("r.png", "image/png", Arrays.copyOf(RepairPhotoTests.PNG, 10)), null, null)
                .andExpect(status().isBadRequest());
        submit(customer, id, receipt("r.png", "image/png", new byte[0]), null, null).andExpect(status().isBadRequest());
        em.flush();
        assertThat(receipts.findPendingByBookingId(id)).isEmpty();

        submit(customer, id, receipt("r.jpg", "image/jpeg", RepairPhotoTests.JPEG), null, null)
                .andExpect(status().isOk());
        submit(customer, id, receipt("r.webp", "image/webp", RepairPhotoTests.WEBP), null, null)
                .andExpect(status().isConflict()); // one receipt waits at a time
    }

    @Test
    void aDisabledMethodCannotBeChosen() throws Exception {
        configure(false, true);
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isConflict());
        choose(id, PaymentMethod.CARD).andExpect(status().isBadRequest());
        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());
        assertThat(payment(id).getMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
    }

    @Test
    void aReceiptNeedsTheChosenMethodAndTheOwnBooking() throws Exception {
        UUID id = signedBooking();
        submit(id).andExpect(status().isConflict()); // nothing chosen yet
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(customer, id, png(), "BANK_TRANSFER", null).andExpect(status().isBadRequest());
        submit(saveUser(Role.USER), id, png(), null, null).andExpect(status().isNotFound());
        submit(customer, id, png(), null, "line one\nline two").andExpect(status().isBadRequest());
        submit(customer, id, png(), null, "R".repeat(101)).andExpect(status().isBadRequest());
    }

    // ---------------------------------------------------------------- receipt access

    @Test
    void theReceiptImageIsForTheCustomerAndAdminsOnly() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);
        String path = "/api/payment-receipts/" + receiptId + "/image";

        mvc.perform(as(customer, get(path))).andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(RepairPhotoTests.PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "inline"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, Matchers.containsString("no-store")));
        mvc.perform(as(admin, get(path))).andExpect(status().isOk());
        mvc.perform(as(saveUser(Role.SUPER_ADMIN), get(path))).andExpect(status().isOk());
        mvc.perform(as(saveUser(Role.USER), get(path))).andExpect(status().isNotFound());
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- approval

    @Test
    void onlyAnAdminCanApproveOrReject() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);

        approve(customer, receiptId, "250").andExpect(status().isForbidden());
        reject(customer, receiptId, "No").andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/payment-receipts/" + receiptId + "/approve"), "{\"amount\": 250}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/admin/payment-receipts/" + receiptId + "/reject"), "{\"reason\": \"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(customer, get("/api/admin/payment-receipts"))).andExpect(status().isForbidden());

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(receipts.findById(receiptId).orElseThrow().getStatus())
                .isEqualTo(PaymentReceipt.Status.PENDING_VERIFICATION);
    }

    @Test
    void approvalActivatesTheBookingThroughTheNormalLifecycle() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);

        mvc.perform(as(admin, get("/api/admin/payment-receipts").param("status", "PENDING_VERIFICATION")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + receiptId + "')].customerName").value(customer.getFullName()))
                .andExpect(jsonPath("$[?(@.id == '" + receiptId + "')].amountDue").value(250.00))
                .andExpect(jsonPath("$[?(@.id == '" + receiptId + "')].transferReference").value("TXN-778899"));

        approve(admin, receiptId, "250").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedBy").value(admin.getFullName()))
                .andExpect(jsonPath("$.reviewedAt").exists());

        // Approved: ready for pickup, not started. The scooter stays AVAILABLE, held by the booking.
        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(b.getActualStartTime()).isNull();
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getPaidAt()).isNotNull();
        List<PaymentTransaction> recorded = recorded(id);
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).getKind()).isEqualTo(PaymentTransaction.Kind.INITIAL);
        assertThat(recorded.get(0).getMethod()).isEqualTo(PaymentMethod.QR_TRANSFER);
        assertThat(recorded.get(0).getReference()).isEqualTo("TXN-778899");
        assertThat(recorded.get(0).getRecordedBy().getUserId()).isEqualTo(admin.getUserId());
        assertThat(recorded.get(0).getProviderRef()).isNull();
        assertThat(messages(customer)).anyMatch(m -> m.contains("Your QR payment of LKR 250.00 was approved."));
        assertNoProviderCalls();
    }

    @Test
    void aSuperAdminApprovesABankTransfer() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());

        approve(saveUser(Role.SUPER_ADMIN), pendingReceipt(id), "250.00").andExpect(status().isOk());

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(b.getActualStartTime()).isNull();
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(messages(customer)).anyMatch(m -> m.contains("Your bank transfer of LKR 250.00 was approved."));
    }

    @Test
    void approvingTwiceRecordsThePaymentOnceAndConfirmsTheBookingOnce() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);

        approve(admin, receiptId, "250").andExpect(status().isOk());
        approve(admin, receiptId, "250").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(recorded(id)).hasSize(1);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("250.00");
        assertThat(messages(customer).stream().filter(m -> m.startsWith("Booking confirmed"))).hasSize(1);
    }

    @Test
    void anAmountOtherThanTheReceiptAmountIsRefused() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);

        approve(admin, receiptId, "200").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(Matchers.startsWith("The amount due is LKR 250.00")));
        approve(admin, receiptId, "0").andExpect(status().isBadRequest());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(recorded(id)).isEmpty();
    }

    // ---------------------------------------------------------------- rejection and replacement

    @Test
    void aRejectedReceiptStaysOnRecordAndAReplacementCanBeApproved() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID first = pendingReceipt(id);

        reject(admin, first, "Transaction not found").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Transaction not found"));
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("Payment receipt not accepted")
                && m.contains("Transaction not found"));
        mvc.perform(as(customer, get("/api/bookings/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.receipts[0].status").value("REJECTED"))
                .andExpect(jsonPath("$.payment.receipts[0].rejectionReason").value("Transaction not found"))
                .andExpect(jsonPath("$.payment.receipts[0].reviewedBy").doesNotExist());

        submit(customer, id, receipt("again.jpg", "image/jpeg", RepairPhotoTests.JPEG), null, "TXN-2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payment.receipts.length()").value(2))
                .andExpect(jsonPath("$.payment.receipts[1].status").value("PENDING_VERIFICATION"));
        UUID second = pendingReceipt(id);

        approve(admin, first, "250").andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        approve(admin, second, "250").andExpect(status().isOk());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(receipts.findById(first).orElseThrow().getStatus()).isEqualTo(PaymentReceipt.Status.REJECTED);
        reject(admin, second, "Too late").andExpect(status().isConflict());
    }

    @Test
    void aRejectionNeedsAReason() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());

        reject(admin, pendingReceipt(id), " ").andExpect(status().isBadRequest());
        assertThat(receipts.findPendingByBookingId(id)).isPresent();
    }

    @Test
    void nothingElseCanBeChosenWhileAReceiptWaits() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.QR_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());

        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isConflict());
        mvc.perform(as(customer, post("/api/bookings/" + id + "/payment-intent"))).andExpect(status().isConflict());
        assertThat(payment(id).getMethod()).isEqualTo(PaymentMethod.QR_TRANSFER);
        assertNoProviderCalls();
    }

    @Test
    void cancellingWhileAReceiptWaitsExpiresItAndTellsTheAdmins() throws Exception {
        UUID id = signedBooking();
        choose(id, PaymentMethod.BANK_TRANSFER).andExpect(status().isOk());
        submit(id).andExpect(status().isOk());
        UUID receiptId = pendingReceipt(id);

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        assertThat(receipts.findById(receiptId).orElseThrow().getStatus()).isEqualTo(PaymentReceipt.Status.EXPIRED);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(messages(admin)).anyMatch(m -> m.startsWith("Unverified receipt"));
        approve(admin, receiptId, "250").andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CANCELLED);
    }

    // ---------------------------------------------------------------- final balance

    @Test
    void theFinalQrBalanceIsTheAdjustedFinalTotalLessTheInitialPayment() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        review(report(id, true, "2000"), "RESOLVED", "1500");

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("BALANCE_DUE"))
                .andExpect(jsonPath("$.pendingTotalCost").value(6500.00))
                .andExpect(jsonPath("$.payment.balanceDue").value(6250.00));
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("Balance due")
                && m.contains("Adjusted final total LKR 6500.00") && m.contains("LKR 6250.00"));

        submit(customer, id, png(), null, "TXN-END").andExpect(status().isBadRequest()); // say which method
        submit(customer, id, png(), "QR_TRANSFER", "TXN-END").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.payment.receipts[1].kind").value("BALANCE"))
                .andExpect(jsonPath("$.payment.receipts[1].amount").value(6250.00))
                .andExpect(jsonPath("$.payment.receipts[1].status").value("PENDING_VERIFICATION"));
        UUID balance = pendingReceipt(id);
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);

        approve(admin, balance, "6500").andExpect(status().isConflict());
        approve(admin, balance, "6250").andExpect(status().isOk());

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(b.getTotalCost()).isEqualByComparingTo("6500.00");
        assertThat(b.getScooter().getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        Payment p = payment(id);
        assertThat(p.getAmount()).isEqualByComparingTo("6500.00");
        assertThat(p.getBalanceDue()).isNull();
        assertThat(recorded(id)).extracting(PaymentTransaction::getKind)
                .containsExactly(PaymentTransaction.Kind.INITIAL, PaymentTransaction.Kind.BALANCE);
        mvc.perform(as(customer, get("/api/bookings/" + id + "/invoices"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].subtotal").value(8000.00))
                .andExpect(jsonPath("$[0].total").value(6500.00))
                .andExpect(jsonPath("$[0].paymentMethod").value("QR_TRANSFER"))
                .andExpect(jsonPath("$[0].amountReceived").value(6500.00))
                .andExpect(jsonPath("$[0].cashReceived").doesNotExist());
        assertThat(messages(customer)).anyMatch(m -> m.contains("Your QR payment of LKR 6250.00 was approved."));
        assertNoProviderCalls();
    }

    @Test
    void theFinalBankBalanceIsTheAdjustedFinalTotalLessTheInitialPayment() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.BANK_TRANSFER);
        review(report(id, true, "2000"), "RESOLVED", "1500");

        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(6250.00));
        submit(customer, id, png(), "BANK_TRANSFER", null).andExpect(status().isOk());
        approve(admin, pendingReceipt(id), "6250").andExpect(status().isOk());

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.COMPLETED);
        assertThat(messages(customer)).anyMatch(m -> m.contains("Your bank transfer of LKR 6250.00 was approved."));
    }

    @Test
    void aClaimAloneDoesNotReduceTheBalanceAndARepairUnderReviewBlocksTheFinalBill() throws Exception {
        UUID claimOnly = rentalOf8000(PaymentMethod.BANK_TRANSFER);
        String claim = report(claimOnly, true, "2000");
        end(claimOnly).andExpect(status().isConflict());
        review(claim, "RESOLVED", "null");
        end(claimOnly).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));

        UUID underReview = rentalOf8000(PaymentMethod.QR_TRANSFER);
        review(report(underReview, true, "2000"), "UNDER_REVIEW", "1500");
        end(underReview).andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("This rental has unresolved repair reports. Resolve them before calculating the final bill."));
        assertThat(booking(underReview).getEndedAt()).isNull();
    }

    @Test
    void aBalanceReceiptIsAcceptedOnlyWhileABalanceIsDue() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        submit(customer, id, png(), "QR_TRANSFER", null).andExpect(status().isConflict()); // not ended yet
        mvc.perform(as(customer, post("/api/bookings/" + id + "/balance-intent"))).andExpect(status().isConflict());
    }

    // ---------------------------------------------------------------- overpayment and refunds

    @Test
    void anOverpaymentLeavesARefundDueThatOnlyAnAdminCompletes() throws Exception {
        // One hour in: the final total is 250, less an approved reimbursement of 100 = 150 against 250 received.
        UUID id = transferRentalUnderWay(PaymentMethod.BANK_TRANSFER, 30);
        review(report(id, true, "100"), "RESOLVED", "100");

        end(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.payment.refundDue").value(100.00))
                .andExpect(jsonPath("$.payment.balanceDue").doesNotExist());
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(messages(admin)).anyMatch(m -> m.startsWith("Refund due"));

        String refund = "{\"amount\": 100, \"reference\": \"BOC-REF-55\", \"note\": \"Sent to the customer's account\"}";
        mvc.perform(json(as(customer, post("/api/admin/bookings/" + id + "/manual-refund")), refund))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/bookings/" + id + "/manual-refund"), refund))
                .andExpect(status().isUnauthorized());
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/cash/refund")), "{\"amount\": 100}"))
                .andExpect(status().isConflict()); // not cash
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/manual-refund")),
                "{\"amount\": 90}")).andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);

        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/manual-refund")), refund))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.payment.refundDue").doesNotExist());
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/manual-refund")), refund))
                .andExpect(status().isOk()); // already recorded

        PaymentTransaction t = recorded(id).stream().filter(x -> x.getKind() == PaymentTransaction.Kind.REFUND)
                .findFirst().orElseThrow();
        assertThat(t.getAmount()).isEqualByComparingTo("100.00");
        assertThat(t.getMethod()).isEqualTo(PaymentMethod.BANK_TRANSFER);
        assertThat(t.getReference()).isEqualTo("BOC-REF-55");
        assertThat(t.getNote()).isEqualTo("Sent to the customer's account");
        assertThat(t.getRecordedBy().getUserId()).isEqualTo(admin.getUserId());
        assertThat(recorded(id).stream().filter(x -> x.getKind() == PaymentTransaction.Kind.REFUND)).hasSize(1);
        assertThat(payment(id).getAmount()).isEqualByComparingTo("150.00");
        assertThat(messages(customer)).anyMatch(m -> m.startsWith("Refund completed"));
        assertNoProviderCalls();
    }

    // ---------------------------------------------------------------- mixing manual methods

    @Test
    void aQrBookingMayPayItsBalanceInCashAtTheCounter() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));

        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/cash/balance")), "{\"amount\": 7750}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertThat(recorded(id)).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.QR_TRANSFER, PaymentMethod.CASH);
    }

    @Test
    void aQrBookingMayPayItsBalanceByBankTransferButNotInCashWhileTheReceiptWaits() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.QR_TRANSFER);
        end(id).andExpect(status().isOk()).andExpect(jsonPath("$.payment.balanceDue").value(7750.00));

        submit(customer, id, png(), "BANK_TRANSFER", "TXN-BAL").andExpect(status().isOk());
        // The counter cannot record the same balance while the transfer waits for verification.
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/cash/balance")), "{\"amount\": 7750}"))
                .andExpect(status().isConflict());
        // Rejected (the money never arrived): the customer pays at the counter instead.
        reject(admin, pendingReceipt(id), "Transaction not found").andExpect(status().isOk());
        mvc.perform(json(as(admin, post("/api/admin/bookings/" + id + "/cash/balance")), "{\"amount\": 7750}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        assertThat(recorded(id)).extracting(PaymentTransaction::getMethod)
                .containsExactly(PaymentMethod.QR_TRANSFER, PaymentMethod.CASH);
        assertThat(payment(id).getMethod()).isEqualTo(PaymentMethod.QR_TRANSFER);
    }

    @Test
    void transfersAreNeverProviderPayments() throws Exception {
        UUID id = rentalOf8000(PaymentMethod.BANK_TRANSFER);
        end(id).andExpect(status().isOk());

        Payment p = payment(id);
        assertThat(p.getProvider()).isNull();
        assertThat(p.getStripePaymentId()).isNull();
        assertThat(p.getPaymentMethodRef()).isNull();
        assertThat(p.getCardLast4()).isNull();
        mvc.perform(as(admin, get("/api/bookings/" + id + "/payment")))
                .andExpect(jsonPath("$.method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.provider").doesNotExist())
                .andExpect(jsonPath("$.transactions[0].method").value("BANK_TRANSFER"))
                .andExpect(jsonPath("$.transactions[0].recordedBy").value(admin.getFullName()));
        assertNoProviderCalls();
        assertThat(settings.findById(PaymentSettings.ID)).isPresent();
    }
}
