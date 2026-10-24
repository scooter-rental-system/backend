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
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Payment;
import com.scooterrentalkandy.spring.entity.PaymentTransaction;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.PaymentTransactionRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
import com.scooterrentalkandy.spring.support.TestPaymentGateway.CreatedIntent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The booking payment lifecycle over HTTP: initial payment intent, provider webhook, activation, failure,
 * cancellation and refund, through the real security chain, services and embedded PostgreSQL. The provider is
 * the test-only {@link TestPaymentGateway}: webhook deliveries are registered with it and posted with its test
 * signature. Bookings are created and signed through the API. Each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class PaymentLifecycleTests extends PostgresIntegrationTest {

    private static final String WEBHOOK = "/api/webhooks/stripe";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestPaymentGateway gateway;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentTransactionRepository transactions;

    @Autowired
    private JwtService jwt;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    @BeforeEach
    void resetGateway() {
        gateway.reset();
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("pay." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Pay Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("Y" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("PY-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("PYP-" + id);
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private GearItem saveGear() {
        GearItem g = new GearItem();
        g.setName("Test Tent " + unique());
        g.setCategory(Category.CAMPING);
        g.setDailyRate(new BigDecimal("500.00"));
        g.setTotalQuantity(5);
        return gearItems.saveAndFlush(g);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** A PENDING booking for {@code scooter} (and one {@code gear} item, if given) created through the API. */
    private UUID book(User customer, Scooter scooter, GearItem gear) throws Exception {
        String gearJson = gear == null ? "" : """
                , "gear": [{"gearItemId": "%s", "quantity": 1}]""".formatted(gear.getGearItemId());
        String body = """
                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"%s}"""
                .formatted(scooter.getScooterId(), LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES),
                        gearJson);
        String response = mvc.perform(json(as(customer, post("/api/bookings")), body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(response, "$.id"));
    }

    private void sign(User customer, UUID bookingId) throws Exception {
        mvc.perform(json(as(customer, post("/api/bookings/" + bookingId + "/contract/sign")), """
                        {"signedName": "%s", "agree": true}""".formatted(customer.getFullName())))
                .andExpect(status().isOk());
    }

    /** A signed PENDING booking, ready to pay. */
    private UUID signedBooking(User customer, Scooter scooter, GearItem gear) throws Exception {
        UUID id = book(customer, scooter, gear);
        sign(customer, id);
        return id;
    }

    private ResultActions requestIntent(User user, UUID bookingId) throws Exception {
        return mvc.perform(as(user, post("/api/bookings/" + bookingId + "/payment-intent")));
    }

    private CreatedIntent intent(User customer, UUID bookingId) throws Exception {
        requestIntent(customer, bookingId).andExpect(status().isOk());
        return gateway.intents().getLast();
    }

    private ResultActions webhook(String payload, String signature) throws Exception {
        return mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(payload)
                .header("Stripe-Signature", signature));
    }

    private void deliver(TestPaymentGateway.Event event) throws Exception {
        webhook(gateway.deliver(event), TestPaymentGateway.SIGNATURE).andExpect(status().isOk());
    }

    private void pay(User customer, UUID bookingId) throws Exception {
        deliver(gateway.succeeded(intent(customer, bookingId), "pm_test_saved", "4242"));
    }

    private Booking booking(UUID id) {
        return bookings.findById(id).orElseThrow();
    }

    private Payment payment(UUID bookingId) {
        return payments.findByBooking_BookingId(bookingId).orElseThrow();
    }

    private PaymentTransaction transaction(Payment p, String providerRef) {
        return transactions.findFirstByPayment_PaymentIdAndProviderRefOrderByCreatedAtDesc(p.getPaymentId(), providerRef)
                .orElseThrow();
    }

    /** In-app notifications only (each notification also writes email and SMS rows). */
    private long notificationsFor(User user) {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND channel = 'IN_APP'",
                Long.class, user.getUserId());
    }

    // ── Initial payment intent ────────────────────────────────────────────────

    @Test
    void theOwnerGetsAnIntentForTheServerCalculatedInitialCharge() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), saveGear());

        // Anything the client sends is ignored: one hour (250.00) plus one gear day (500.00).
        mvc.perform(json(as(owner, post("/api/bookings/" + id + "/payment-intent")),
                        """
                        {"amount": 1, "currency": "USD"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").value(id.toString()))
                .andExpect(jsonPath("$.purpose").value("INITIAL"))
                .andExpect(jsonPath("$.amount").value(750.00))
                .andExpect(jsonPath("$.currency").value("LKR"))
                .andExpect(jsonPath("$.provider").value("test"))
                .andExpect(jsonPath("$.clientSecret").isString());

        CreatedIntent created = gateway.intents().getFirst();
        assertThat(gateway.intents()).hasSize(1);
        assertThat(created.amount()).isEqualByComparingTo("750.00");
        assertThat(created.currency()).isEqualTo("LKR");
        assertThat(created.metadata()).containsEntry("bookingId", id.toString()).containsEntry("purpose", "INITIAL");
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getAmount()).isEqualByComparingTo("750.00");
        assertThat(p.getStripePaymentId()).isEqualTo(created.id());
        assertThat(transaction(p, created.id()).getStatus()).isEqualTo(PaymentTransaction.Status.PENDING);
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    @Test
    void onlyTheOwnerCanStartPayment() throws Exception {
        UUID id = signedBooking(saveUser(Role.USER), saveScooter(), null);

        requestIntent(saveUser(Role.USER), id).andExpect(status().isNotFound());
        // Admins may view a customer's booking but never pay for it.
        requestIntent(saveUser(Role.ADMIN), id).andExpect(status().isForbidden());
        mvc.perform(post("/api/bookings/" + id + "/payment-intent")).andExpect(status().isUnauthorized());
        requestIntent(saveUser(Role.USER), UUID.randomUUID()).andExpect(status().isNotFound());

        assertThat(gateway.intents()).isEmpty();
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
    }

    @Test
    void theContractMustBeSignedBeforePaying() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, saveScooter(), null);

        requestIntent(owner, id)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Please sign the rental agreement before paying"));

        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void onlyAPendingBookingCanBePaid() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        requestIntent(owner, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This booking is not awaiting payment (status CANCELLED)"));
        assertThat(gateway.intents()).isEmpty();
    }

    @Test
    void aNewAttemptCancelsAndReplacesTheOpenIntent() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent first = intent(owner, id);

        CreatedIntent second = intent(owner, id);

        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(gateway.cancelled()).containsExactly(first.id());
        Payment p = payment(id);
        assertThat(p.getStripePaymentId()).isEqualTo(second.id());
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        PaymentTransaction replaced = transaction(p, first.id());
        assertThat(replaced.getStatus()).isEqualTo(PaymentTransaction.Status.FAILED);
        assertThat(replaced.getFailureReason()).isEqualTo("Replaced by a new payment attempt");
        assertThat(transaction(p, second.id()).getStatus()).isEqualTo(PaymentTransaction.Status.PENDING);
    }

    // ── Provider webhook ──────────────────────────────────────────────────────

    @Test
    void aSuccessfulPaymentActivatesTheBookingAndRentsTheScooter() throws Exception {
        User owner = saveUser(Role.USER);
        Scooter scooter = saveScooter();
        UUID id = signedBooking(owner, scooter, null);
        CreatedIntent created = intent(owner, id);
        long notified = notificationsFor(owner);

        deliver(gateway.succeeded(created, "pm_test_saved", "4242"));

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(scooters.findById(scooter.getScooterId()).orElseThrow().getStatus())
                .isEqualTo(ScooterStatus.RENTED);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(p.getPaidAt()).isNotNull();
        assertThat(p.getPaymentMethodRef()).isEqualTo("pm_test_saved");
        assertThat(p.getCardLast4()).isEqualTo("4242");
        assertThat(transaction(p, created.id()).getStatus()).isEqualTo(PaymentTransaction.Status.SUCCESS);
        assertThat(notificationsFor(owner)).isEqualTo(notified + 1);
    }

    @Test
    void aDeclinedPaymentLeavesTheBookingPayable() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent created = intent(owner, id);

        deliver(gateway.failed(created, "Your card was declined."));

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("Your card was declined.");
        assertThat(transaction(p, created.id()).getStatus()).isEqualTo(PaymentTransaction.Status.FAILED);

        // The customer can try again with a new intent.
        CreatedIntent retry = intent(owner, id);
        assertThat(retry.id()).isNotEqualTo(created.id());
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void aRepeatedSuccessDeliveryChangesNothing() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent created = intent(owner, id);
        TestPaymentGateway.Event success = gateway.succeeded(created, "pm_test_saved", "4242");
        deliver(success);
        LocalDateTime paidAt = payment(id).getPaidAt();
        long notified = notificationsFor(owner);

        deliver(success);

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(payment(id).getPaidAt()).isEqualTo(paidAt);
        assertThat(notificationsFor(owner)).isEqualTo(notified);
        assertThat(gateway.refunds()).isEmpty();
    }

    @Test
    void aWebhookWithABadSignatureIsRejected() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        String payload = gateway.deliver(gateway.succeeded(intent(owner, id), "pm_test_saved", "4242"));

        webhook(payload, "forged").andExpect(status().isBadRequest());
        mvc.perform(post(WEBHOOK).contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isBadRequest());

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    @Test
    void aPaidBookingCannotBePaidAgain() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        pay(owner, id);

        requestIntent(owner, id).andExpect(status().isConflict());
        assertThat(gateway.intents()).hasSize(1);
    }

    @Test
    void theOwnerSeesThePaymentWithItsProviderHistory() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        pay(owner, id);

        mvc.perform(as(owner, get("/api/bookings/" + id + "/payment")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.amount").value(250.00))
                .andExpect(jsonPath("$.transactions.length()").value(1))
                .andExpect(jsonPath("$.transactions[0].kind").value("INITIAL"))
                .andExpect(jsonPath("$.transactions[0].status").value("SUCCESS"));
        mvc.perform(as(saveUser(Role.USER), get("/api/bookings/" + id + "/payment")))
                .andExpect(status().isNotFound());
    }

    // ── Provider details ──────────────────────────────────────────────────────

    @Test
    void theConfigurationNamesTheProviderAndItsPublishableKey() throws Exception {
        mvc.perform(as(saveUser(Role.USER), get("/api/payments/config")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.provider").value("test"))
                .andExpect(jsonPath("$.publishableKey").value("test-publishable-key"))
                .andExpect(jsonPath("$.currency").value("LKR"));
    }

    @Test
    void theCustomersProviderAccountIsCreatedOnceAndNeverShown() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);

        CreatedIntent first = intent(owner, id);
        CreatedIntent second = intent(owner, id);
        UUID other = signedBooking(owner, saveScooter(), null);
        CreatedIntent third = intent(owner, other);

        assertThat(gateway.customers()).hasSize(1);
        TestPaymentGateway.Customer customer = gateway.customers().getFirst();
        assertThat(customer.userId()).isEqualTo(owner.getUserId());
        assertThat(customer.email()).isEqualTo(owner.getEmail());
        assertThat(customer.idempotencyKey()).isEqualTo("customer-" + owner.getUserId());
        assertThat(users.findById(owner.getUserId()).orElseThrow().getStripeCustomerId()).isEqualTo(customer.id());
        assertThat(first.customerId()).isEqualTo(customer.id());
        assertThat(second.customerId()).isEqualTo(customer.id());
        assertThat(third.customerId()).isEqualTo(customer.id());
        mvc.perform(as(owner, get("/api/me")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stripeCustomerId").doesNotExist());
        mvc.perform(as(owner, get("/api/bookings/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$..stripeCustomerId").isEmpty());
    }

    @Test
    void intentIdempotencyKeysAreTheBookingAndAttemptNumber() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);

        CreatedIntent first = intent(owner, id);
        CreatedIntent second = intent(owner, id);

        assertThat(first.idempotencyKey()).isEqualTo("booking-" + id + "-initial-intent-1");
        assertThat(second.idempotencyKey()).isEqualTo("booking-" + id + "-initial-intent-2");
    }

    @Test
    void anIntentTheProviderCanNoLongerCancelIsNotReplaced() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent first = intent(owner, id);
        gateway.notCancelable(first.id()); // the customer may be paying it right now

        requestIntent(owner, id)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Your previous payment attempt is still being processed; "
                        + "please wait a moment and refresh"));

        assertThat(gateway.intents()).hasSize(1);
        assertThat(payment(id).getStripePaymentId()).isEqualTo(first.id());
        assertThat(transaction(payment(id), first.id()).getStatus()).isEqualTo(PaymentTransaction.Status.PENDING);
    }

    // ── Webhook integrity ─────────────────────────────────────────────────────

    @Test
    void aSuccessForAnotherAmountIsNotAcceptedAsPayment() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent created = intent(owner, id);

        deliver(gateway.succeeded(created.id(), new BigDecimal("1.00"), "LKR", created.metadata(), "pm_test", "4242"));

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("The provider reported a different amount or currency");
        assertThat(p.getPaidAt()).isNull();
        assertThat(transaction(p, created.id()).getStatus()).isEqualTo(PaymentTransaction.Status.FAILED);
    }

    @Test
    void aSuccessInAnotherCurrencyIsNotAcceptedAsPayment() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent created = intent(owner, id);

        deliver(gateway.succeeded(created.id(), created.amount(), "USD", created.metadata(), "pm_test", "4242"));

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void aSuccessForAnotherBookingsIntentIsNotAccepted() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        UUID other = signedBooking(owner, saveScooter(), null);
        CreatedIntent otherIntent = intent(owner, other);
        intent(owner, id);

        // Correctly signed, but the intent belongs to the other booking.
        deliver(gateway.succeeded(otherIntent.id(), otherIntent.amount(), "LKR",
                java.util.Map.of("bookingId", id.toString(), "purpose", "INITIAL"), "pm_test", "4242"));

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
    }

    /**
     * The event id is stored when processed. To show the second delivery is skipped rather than merely harmless,
     * the booking and payment are put back to awaiting payment (as if the first delivery had not happened).
     */
    @Test
    void anEventIsProcessedOnlyOnce() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        TestPaymentGateway.Event success = gateway.succeeded(intent(owner, id), "pm_test_saved", "4242");
        deliver(success);
        long notified = notificationsFor(owner);
        Booking b = booking(id);
        b.setStatus(BookingStatus.PENDING);
        Payment p = payment(id);
        p.setStatus(PaymentStatus.PENDING);
        em.flush();

        deliver(success);

        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(notificationsFor(owner)).isEqualTo(notified);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_provider_events WHERE event_id = ?", Long.class,
                success.id())).isEqualTo(1);
    }

    @Test
    void thePublicMockStripeEndpointIsGone() throws Exception {
        String confirm = "/mock-stripe/v1/payment_intents/pi_any/confirm";

        mvc.perform(post(confirm).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(as(saveUser(Role.USER), post(confirm)).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    // ── Cancellation and refund ───────────────────────────────────────────────

    @Test
    void cancellingAnUnpaidBookingClosesItsOpenIntent() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = signedBooking(owner, saveScooter(), null);
        CreatedIntent created = intent(owner, id);

        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        assertThat(gateway.cancelled()).containsExactly(created.id());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        Payment p = payment(id);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("Booking cancelled before payment was completed");
        assertThat(gateway.refunds()).isEmpty();
    }

    @Test
    void cancellingAPaidBookingBeforeItStartsRefundsInFull() throws Exception {
        User owner = saveUser(Role.USER);
        Scooter scooter = saveScooter();
        UUID id = signedBooking(owner, scooter, saveGear());
        pay(owner, id);
        String intentId = payment(id).getStripePaymentId();

        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(gateway.refunds()).hasSize(1);
        assertThat(gateway.refunds().getFirst().intentId()).isEqualTo(intentId);
        assertThat(gateway.refunds().getFirst().amount()).isEqualByComparingTo("750.00");
        assertThat(payment(id).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(scooters.findById(scooter.getScooterId()).orElseThrow().getStatus())
                .isEqualTo(ScooterStatus.AVAILABLE);
    }
}
