package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Invoice;
import com.scooterrentalkandy.spring.entity.InvoiceLine;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.PaymentStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.InvoiceRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestPaymentGateway;
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
 * The pickup step and odometer billing over HTTP (BE-046): a paid booking is READY_FOR_PICKUP and holds its scooter;
 * an admin confirms the handover with the starting odometer, then the customer confirms the start, which is the
 * actual (server-time) start: the booking becomes ACTIVE and the scooter RENTED. The rental is billed from that
 * actual start, and its distance is the odometer difference an admin records at return; GPS is monitoring only.
 * Payments go through the test-only {@link TestPaymentGateway}. Each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class RentalHandoverTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private TestPaymentGateway gateway;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private InvoiceRepository invoices;

    @Autowired
    private GpsPingRepository pings;

    @Autowired
    private JwtService jwt;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager em;

    private User customer;
    private User admin;

    @BeforeEach
    void setUp() {
        gateway.reset();
        customer = saveUser(Role.USER);
        admin = saveUser(Role.ADMIN);
    }

    // ── Fixtures ──────────────────────────────────────────────────────────────

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("handover." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Handover " + role + " " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("H" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("HO-" + id);
        s.setModel("Handover Dio");
        s.setPlateNumber("HOP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private void deliver(TestPaymentGateway.Event event) throws Exception {
        mvc.perform(post("/api/webhooks/stripe").contentType(MediaType.APPLICATION_JSON)
                        .content(gateway.deliver(event)).header("Stripe-Signature", TestPaymentGateway.SIGNATURE))
                .andExpect(status().isOk());
    }

    /** A booking for {@code scooter} two days ahead, signed and paid by card: READY_FOR_PICKUP. */
    private UUID paidBooking(User who, Scooter scooter) throws Exception {
        String created = mvc.perform(json(as(who, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                        .formatted(scooter.getScooterId(),
                                LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(JsonPath.read(created, "$.id"));
        mvc.perform(json(as(who, post("/api/bookings/" + id + "/contract/sign")),
                "{\"signedName\": \"%s\", \"agree\": true}".formatted(who.getFullName()))).andExpect(status().isOk());
        mvc.perform(as(who, post("/api/bookings/" + id + "/payment-intent"))).andExpect(status().isOk());
        deliver(gateway.succeeded(gateway.intents().getLast(), "pm_test_saved", "4242"));
        return id;
    }

    private UUID paidBooking() throws Exception {
        return paidBooking(customer, saveScooter());
    }

    private ResultActions handover(User who, UUID id, String body) throws Exception {
        return mvc.perform(json(as(who, post("/api/admin/bookings/" + id + "/handover")), body));
    }

    private ResultActions handover(UUID id, String startOdometerKm) throws Exception {
        return handover(admin, id, "{\"startOdometerKm\": " + startOdometerKm + "}");
    }

    private ResultActions start(User who, UUID id) throws Exception {
        return mvc.perform(as(who, post("/api/bookings/" + id + "/start")));
    }

    private ResultActions end(User who, UUID id, String endOdometerKm) throws Exception {
        return mvc.perform(json(as(who, put("/api/bookings/" + id + "/complete")),
                endOdometerKm == null ? "{}" : "{\"endOdometerKm\": " + endOdometerKm + "}"));
    }

    private ResultActions location(User who, UUID id) throws Exception {
        return mvc.perform(json(as(who, post("/api/bookings/" + id + "/location")),
                "{\"latitude\": 7.2906, \"longitude\": 80.6337}"));
    }

    /** Handed over at {@code startOdometerKm}, started by the customer, then moved to have started 150 minutes ago. */
    private UUID rentalStarted150MinutesAgo(String startOdometerKm) throws Exception {
        UUID id = paidBooking();
        handover(id, startOdometerKm).andExpect(status().isOk());
        start(customer, id).andExpect(status().isOk());
        Booking b = booking(id);
        b.setActualStartTime(LocalDateTime.now().minusMinutes(150));
        b.setCustomerStartConfirmedAt(b.getActualStartTime());
        em.flush();
        return id;
    }

    private Booking booking(UUID id) {
        em.flush();
        return bookings.findById(id).orElseThrow();
    }

    private ScooterStatus scooterStatus(UUID bookingId) {
        return scooters.findById(booking(bookingId).getScooter().getScooterId()).orElseThrow().getStatus();
    }

    private long notifications(User user, String subjectPrefix) {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND channel = 'IN_APP' "
                + "AND subject LIKE ?", Long.class, user.getUserId(), subjectPrefix + "%");
    }

    private void ping(Booking b, String lat, String lng, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(b.getScooter());
        p.setBooking(b);
        p.setLatitude(new BigDecimal(lat));
        p.setLongitude(new BigDecimal(lng));
        p.setSpeedKmh(new BigDecimal("20.00"));
        p.setRecordedAt(at);
        pings.save(p);
    }

    // ── Payment makes the booking ready for pickup ────────────────────────────

    @Test
    void aPaidBookingWaitsForPickupAndKeepsTheScooterReserved() throws Exception {
        Scooter scooter = saveScooter();
        UUID id = paidBooking(customer, scooter);

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(b.getActualStartTime()).isNull();
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.AVAILABLE);
        mvc.perform(as(customer, get("/api/bookings/" + id))).andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.handoverConfirmedAt").doesNotExist())
                .andExpect(jsonPath("$.actualStartTime").doesNotExist());

        // Held: nobody else can book it, and it is not listed as bookable, although its status says AVAILABLE.
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy"}"""
                        .formatted(scooter.getScooterId(), LocalDateTime.now().plusDays(3).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/scooters/" + scooter.getScooterId())).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.available").value(false));
        // Nor can it go to the workshop while it is promised to a customer.
        mvc.perform(as(admin, patch("/api/admin/scooters/" + scooter.getScooterId() + "/status"))
                        .param("value", "MAINTENANCE"))
                .andExpect(status().isConflict());
    }

    // ── Admin handover ────────────────────────────────────────────────────────

    @Test
    void theCustomerCannotStartBeforeTheHandover() throws Exception {
        UUID id = paidBooking();

        start(customer, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Our team has not handed the scooter over yet. Confirm the "
                        + "start once you have received the scooter"));

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(b.getActualStartTime()).isNull();
        assertThat(b.getCustomerStartConfirmedAt()).isNull();
    }

    @Test
    void theHandoverNeedsAValidStartingOdometer() throws Exception {
        UUID id = paidBooking();

        handover(admin, id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter the starting odometer reading"));
        mvc.perform(as(admin, post("/api/admin/bookings/" + id + "/handover"))).andExpect(status().isBadRequest());
        handover(id, "-0.1").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The starting odometer reading cannot be negative"));
        handover(id, "1000.25").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "Enter the starting odometer reading in km with at most one decimal place"));
        handover(id, "10000000").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The starting odometer reading is too large"));
        handover(admin, id, "{\"startOdometerKm\": \"lots\"}").andExpect(status().isBadRequest());
        assertThat(booking(id).getHandoverConfirmedAt()).isNull();

        handover(id, "0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.startOdometerKm").value(0.0))
                .andExpect(jsonPath("$.handoverConfirmedBy").value(admin.getFullName()))
                .andExpect(jsonPath("$.handoverConfirmedAt").exists())
                .andExpect(jsonPath("$.actualStartTime").doesNotExist());
        // Handed over, but the rental has not started: the scooter is not RENTED yet.
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(notifications(customer, "Scooter handed over")).isEqualTo(1);
    }

    @Test
    void theStartingOdometerCannotBeBelowTheScootersLastRecordedReading() throws Exception {
        Scooter scooter = saveScooter();
        UUID first = paidBooking(customer, scooter);
        handover(first, "4200.0").andExpect(status().isOk());
        start(customer, first).andExpect(status().isOk());
        end(admin, first, "4230.0").andExpect(status().isOk());

        UUID second = paidBooking(customer, scooter);
        handover(second, "4229.9").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The odometer of " + scooter.getCode()
                        + " last read 4230.0 km; the starting reading cannot be lower"));
        handover(second, "4230.0").andExpect(status().isOk());
    }

    @Test
    void onlyAnAdminConfirmsTheHandover() throws Exception {
        UUID id = paidBooking();
        String body = "{\"startOdometerKm\": 1000.0}";

        handover(customer, id, body).andExpect(status().isForbidden());
        handover(saveUser(Role.USER), id, body).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/bookings/" + id + "/handover"), body)).andExpect(status().isUnauthorized());
        assertThat(booking(id).getHandoverConfirmedAt()).isNull();

        handover(saveUser(Role.SUPER_ADMIN), id, body).andExpect(status().isOk());
    }

    @Test
    void theHandoverIsConfirmedOnceAndItsReadingCannotBeRewritten() throws Exception {
        UUID id = paidBooking();
        handover(id, "1000.0").andExpect(status().isOk());
        LocalDateTime confirmedAt = booking(id).getHandoverConfirmedAt();

        handover(id, "1000.0").andExpect(status().isOk()).andExpect(jsonPath("$.startOdometerKm").value(1000.0));
        handover(id, "1100.0").andExpect(status().isConflict());

        Booking b = booking(id);
        assertThat(b.getHandoverConfirmedAt()).isEqualTo(confirmedAt);
        assertThat(b.getStartOdometerKm()).isEqualByComparingTo("1000.0");
        assertThat(notifications(customer, "Scooter handed over")).isEqualTo(1);

        // Still fixed once the rental is under way.
        start(customer, id).andExpect(status().isOk());
        handover(id, "1000.0").andExpect(status().isOk());
        handover(id, "999.0").andExpect(status().isConflict());
        assertThat(booking(id).getStartOdometerKm()).isEqualByComparingTo("1000.0");
    }

    @Test
    void onlyAPaidBookingReadyForPickupCanBeHandedOver() throws Exception {
        String created = mvc.perform(json(as(customer, post("/api/bookings")), """
                        {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy"}"""
                        .formatted(saveScooter().getScooterId(),
                                LocalDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MINUTES))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        UUID unpaid = UUID.fromString(JsonPath.read(created, "$.id"));

        handover(unpaid, "1000.0").andExpect(status().isConflict());
        handover(UUID.randomUUID(), "1000.0").andExpect(status().isNotFound());
        assertThat(booking(unpaid).getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    // ── Customer start ────────────────────────────────────────────────────────

    @Test
    void theCustomersConfirmationStartsTheRentalAtServerTime() throws Exception {
        UUID id = paidBooking();
        LocalDateTime planned = booking(id).getStartTime();
        handover(id, "1000.0").andExpect(status().isOk());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.AVAILABLE);

        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        start(customer, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.stage").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.actualStartTime").exists())
                .andExpect(jsonPath("$.customerStartConfirmedAt").exists());
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(b.getActualStartTime()).isBetween(before, after);
        assertThat(b.getCustomerStartConfirmedAt()).isEqualTo(b.getActualStartTime());
        // The planned pickup time (two days ahead) is kept as it was; the rental runs from the actual start.
        assertThat(b.getStartTime()).isEqualTo(planned);
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.RENTED);
        assertThat(notifications(customer, "Rental started")).isEqualTo(1);
        assertThat(notifications(admin, "Rental started")).isEqualTo(1);
    }

    @Test
    void onlyTheCustomerConfirmsTheirOwnStart() throws Exception {
        UUID id = paidBooking();
        handover(id, "1000.0").andExpect(status().isOk());

        start(saveUser(Role.USER), id).andExpect(status().isNotFound());
        start(admin, id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Only the customer can confirm the start of their rental"));
        mvc.perform(post("/api/bookings/" + id + "/start")).andExpect(status().isUnauthorized());

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(b.getActualStartTime()).isNull();
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void aRepeatedStartConfirmationChangesNothing() throws Exception {
        UUID id = paidBooking();
        handover(id, "1000.0").andExpect(status().isOk());
        start(customer, id).andExpect(status().isOk());
        LocalDateTime startedAt = booking(id).getActualStartTime();

        start(customer, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        start(customer, id).andExpect(status().isOk());

        Booking b = booking(id);
        assertThat(b.getActualStartTime()).isEqualTo(startedAt);
        assertThat(b.getCustomerStartConfirmedAt()).isEqualTo(startedAt);
        assertThat(notifications(customer, "Rental started")).isEqualTo(1);
        assertThat(notifications(admin, "Rental started")).isEqualTo(1);
    }

    // ── GPS: monitoring from the actual start, never billed ───────────────────

    @Test
    void gpsIsRefusedUntilTheRentalActuallyStartsAndAcceptedAfter() throws Exception {
        UUID id = paidBooking();
        // Past its planned pickup time, but not started: no GPS for the rental.
        Booking b = booking(id);
        b.setStartTime(LocalDateTime.now().minusHours(1));
        em.flush();

        location(customer, id).andExpect(status().isConflict());
        handover(id, "1000.0").andExpect(status().isOk());
        location(customer, id).andExpect(status().isConflict());
        mvc.perform(as(customer, get("/api/bookings/" + id + "/tracking"))).andExpect(status().isConflict());
        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(id)).isEmpty();

        start(customer, id).andExpect(status().isOk());
        location(customer, id).andExpect(status().isAccepted());
        assertThat(pings.findByBooking_BookingIdOrderByRecordedAtAsc(id)).hasSize(1);
    }

    @Test
    void movingThePhoneDoesNotChangeTheBillWhenTheOdometerIsUnchanged() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");
        Booking b = booking(id);
        // The customer's phone travelled about 111 km (one degree of latitude) without the scooter moving.
        ping(b, "7.290600", "80.633700", LocalDateTime.now().minusMinutes(100));
        ping(b, "8.290600", "80.633700", LocalDateTime.now().minusMinutes(50));
        em.flush();
        String tracking = mvc.perform(as(customer, get("/api/bookings/" + id + "/tracking")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(new BigDecimal(JsonPath.read(tracking, "$.distanceKm").toString())).isGreaterThan(new BigDecimal("100"));

        end(admin, id, "1000.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.distanceKm").value(0.0))
                .andExpect(jsonPath("$.breakdown.excessKm").value(0.0))
                .andExpect(jsonPath("$.totalCost").value(750.00)); // 3 hours, no distance charge

        // The admin history still shows the tracked route, separately from the billed distance.
        mvc.perform(as(admin, get("/api/admin/gps/bookings/" + id + "/history"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.points.length()").value(2));
    }

    // ── Billing from the actual start, distance from the odometer ─────────────

    @Test
    void aLatePickupIsNotChargedForTheTimeBeforeTheActualStart() throws Exception {
        UUID id = paidBooking();
        // Planned for five hours ago; collected only now.
        Booking b = booking(id);
        b.setStartTime(LocalDateTime.now().minusHours(5));
        em.flush();
        handover(id, "1000.0").andExpect(status().isOk());
        start(customer, id).andExpect(status().isOk());

        // Returned at once: the one-hour minimum (250, already paid), not five hours from the planned time.
        end(admin, id, "1000.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.billableHours").value(1))
                .andExpect(jsonPath("$.totalCost").value(250.00));
        assertThat(gateway.charges()).isEmpty();
    }

    @Test
    void theExcessDistanceIsBilledFromTheOdometerNotFromGps() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");
        Booking b = booking(id);
        // GPS tracked only about 1.1 km.
        ping(b, "7.290600", "80.633700", LocalDateTime.now().minusMinutes(100));
        ping(b, "7.300600", "80.633700", LocalDateTime.now().minusMinutes(50));
        em.flush();

        // 1250.0 - 1000.0 = 250 km: 120 km included, 130 km x 15 = 1,950; 3 hours x 250 = 750.
        end(admin, id, "1250.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startOdometerKm").value(1000.0))
                .andExpect(jsonPath("$.endOdometerKm").value(1250.0))
                .andExpect(jsonPath("$.distanceKm").value(250.00))
                .andExpect(jsonPath("$.breakdown.includedKm").value(120.00))
                .andExpect(jsonPath("$.breakdown.excessKm").value(130.00))
                .andExpect(jsonPath("$.totalCost").value(2700.00));
        assertThat(gateway.charges()).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("2450.00"));

        Invoice inv = invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow();
        assertThat(inv.getTotal()).isEqualByComparingTo("2700.00");
        assertThat(inv.getLines()).extracting(InvoiceLine::getDescription)
                .contains("Distance by odometer (1000.0 to 1250.0): 250.00 km, 120.00 km included; excess km");
        Scooter s = scooters.findById(b.getScooter().getScooterId()).orElseThrow();
        assertThat(s.getStatus()).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(s.getTotalMileage()).isEqualByComparingTo("250.00");
    }

    @Test
    void theEndingOdometerIsRequiredValidAndEnteredByAnAdmin() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");

        end(customer, id, null).andExpect(status().isForbidden());
        end(customer, id, "1010.0").andExpect(status().isForbidden());
        end(admin, id, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter the ending odometer reading"));
        end(admin, id, "999.9").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "The ending odometer (999.9 km) cannot be lower than the starting odometer (1000.0 km)"));
        end(admin, id, "1010.05").andExpect(status().isBadRequest());

        Booking b = booking(id);
        assertThat(b.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(b.getEndedAt()).isNull();
        assertThat(b.getEndOdometerKm()).isNull();
        assertThat(invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL)).isEmpty();
        assertThat(gateway.charges()).isEmpty();
    }

    /**
     * The manual test: a rental handed over at 2586 km with a self-arranged repair under review and noisy phone GPS.
     * Ending is refused until the repair is resolved; then the ending odometer gives the distance, the end is server
     * time, and the approved reimbursement is applied exactly once.
     */
    @Test
    void anUnresolvedRepairBlocksTheEndUntilResolvedThenTheOdometerBillIncludesTheReimbursementOnce()
            throws Exception {
        UUID id = rentalStarted150MinutesAgo("2586.0");
        Booking b = booking(id);
        ping(b, "7.290600", "80.633700", LocalDateTime.now().minusMinutes(100));
        ping(b, "7.302900", "80.633700", LocalDateTime.now().minusMinutes(99)); // a 1.37 km jump in a minute
        em.flush();
        String report = mvc.perform(json(as(customer, post("/api/bookings/" + id + "/repairs")),
                        "{\"description\": \"Puncture repaired at a roadside shop\", \"selfArranged\": true, "
                                + "\"claimedAmount\": 500}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String reportId = JsonPath.read(report, "$.id");

        // 1. Unresolved: refused with the explanation, nothing frozen.
        end(admin, id, "2600.0").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        com.scooterrentalkandy.spring.services.RepairService.UNRESOLVED_REPAIRS));
        assertThat(booking(id).getEndedAt()).isNull();
        assertThat(booking(id).getEndOdometerKm()).isNull();
        assertThat(invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL)).isEmpty();

        // 2. Reviewed and resolved with an approved 300 (the 500 claimed does not count).
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                        "{\"status\": \"RESOLVED\", \"adminNote\": \"Receipt checked\", \"approvedAmount\": 300}"))
                .andExpect(status().isOk());

        // 3. Ended with the odometer at 2600.0: 14 km (within 120 km), 3 hours x 250 = 750, less 300 = 450.
        LocalDateTime before = LocalDateTime.now().minusSeconds(1);
        end(admin, id, "2600.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startOdometerKm").value(2586.0))
                .andExpect(jsonPath("$.endOdometerKm").value(2600.0))
                .andExpect(jsonPath("$.distanceKm").value(14.00))
                .andExpect(jsonPath("$.totalCost").value(450.00));
        LocalDateTime after = LocalDateTime.now().plusSeconds(1);

        Booking ended = booking(id);
        assertThat(ended.getEndTime()).isBetween(before, after);
        Invoice inv = invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow();
        assertThat(inv.getSubtotal()).isEqualByComparingTo("750.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("450.00");
        assertThat(inv.getLines()).filteredOn(l -> l.getDescription().startsWith("Approved repair reimbursement"))
                .singleElement().satisfies(l -> assertThat(l.getAmount()).isEqualByComparingTo("-300.00"));
        assertThat(inv.getLines()).extracting(InvoiceLine::getDescription)
                .contains("Distance by odometer (2586.0 to 2600.0): 14.00 km, 120.00 km included; excess km");
        // 450 billed, 250 paid at booking: the card is charged the 200 left.
        assertThat(gateway.charges()).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("200.00"));
    }

    /**
     * The manual browser test, exactly: a repair saved with an approved 200 but Status still Reported keeps blocking
     * the end; saved as Resolved it is persisted (the PUT answer and a fresh GET agree), the end is no longer blocked,
     * 2752 - 2586 = 166 km is billed, and the 200 is deducted once.
     */
    @Test
    void theManualTestRepairResolvedWithTwoHundredThenEndedAt2752() throws Exception {
        UUID id = rentalStarted150MinutesAgo("2586.0");
        String report = mvc.perform(json(as(customer, post("/api/bookings/" + id + "/repairs")),
                        "{\"description\": \"Puncture repaired at a roadside shop\", \"selfArranged\": true, "
                                + "\"claimedAmount\": 250}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String reportId = JsonPath.read(report, "$.id");
        String review = "{\"status\": \"%s\", \"adminNote\": \"sorry, thank you\", \"approvedAmount\": 200}";

        // 1. Approved amount saved, Status left on Reported: still unresolved, the end is refused.
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)), review.formatted("REPORTED")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REPORTED"));
        end(admin, id, "2752.0").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        com.scooterrentalkandy.spring.services.RepairService.UNRESOLVED_REPAIRS));
        assertThat(booking(id).getEndedAt()).isNull();

        // 2. REPORTED -> RESOLVED with 200: the answer and a fresh GET both say RESOLVED, 200.
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)), review.formatted("RESOLVED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.approvedAmount").value(200.00));
        em.flush();
        em.clear();
        mvc.perform(as(admin, get("/api/bookings/" + id + "/repairs"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("RESOLVED"))
                .andExpect(jsonPath("$[0].approvedAmount").value(200.00))
                .andExpect(jsonPath("$[0].adminNote").value("sorry, thank you"));
        mvc.perform(as(admin, get("/api/admin/repairs"))).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + reportId + "')].status").value("RESOLVED"));

        // 3-5. No longer blocked: 166 km, 3 hours x 250 = 750, 46 km over 120 x 15 = 690, less 200 once = 1,240.
        end(admin, id, "2752.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.startOdometerKm").value(2586.0))
                .andExpect(jsonPath("$.endOdometerKm").value(2752.0))
                .andExpect(jsonPath("$.distanceKm").value(166.00))
                .andExpect(jsonPath("$.breakdown.excessKm").value(46.00))
                .andExpect(jsonPath("$.totalCost").value(1240.00));
        Invoice inv = invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL).orElseThrow();
        assertThat(inv.getSubtotal()).isEqualByComparingTo("1440.00");
        assertThat(inv.getTotal()).isEqualByComparingTo("1240.00");
        assertThat(inv.getLines()).filteredOn(l -> l.getDescription().startsWith("Approved repair reimbursement"))
                .singleElement().satisfies(l -> assertThat(l.getAmount()).isEqualByComparingTo("-200.00"));
        assertThat(gateway.charges()).singleElement()
                .satisfies(c -> assertThat(c.amount()).isEqualByComparingTo("990.00"));
        // The bill is frozen: the reimbursement can no longer change.
        mvc.perform(json(as(admin, put("/api/admin/repairs/" + reportId)),
                        "{\"status\": \"RESOLVED\", \"adminNote\": null, \"approvedAmount\": 250}"))
                .andExpect(status().isConflict());
    }

    @Test
    void aValidEndingReadingIsBilledAsReadWhateverTheAverageSpeed() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");

        // 400 km in two and a half hours: the physical reading is what is billed; speed is monitored separately.
        // 3 hours x 250 = 750; 400 - 120 km included = 280 km x 15 = 4,200.
        end(admin, id, "1400.0").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.distanceKm").value(400.00))
                .andExpect(jsonPath("$.totalCost").value(4950.00));
    }

    @Test
    void aRentalStartedThroughTheHandoverNeverFallsBackToGpsDistance() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");
        Booking b = booking(id);
        // About 111 km tracked by GPS.
        ping(b, "7.290600", "80.633700", LocalDateTime.now().minusMinutes(100));
        ping(b, "8.290600", "80.633700", LocalDateTime.now().minusMinutes(50));
        em.flush();

        // No ending reading: refused, never billed from GPS instead.
        end(admin, id, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Enter the ending odometer reading"));
        assertThat(booking(id).getEndedAt()).isNull();
        assertThat(invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL)).isEmpty();

        // Even if the starting reading were lost (only possible by bypassing the V22 constraint, which this
        // test transaction drops and rolls back), the bill is refused rather than calculated from GPS.
        jdbc.execute("ALTER TABLE bookings DROP CONSTRAINT ck_bookings_handover");
        jdbc.update("UPDATE bookings SET start_odometer_km = NULL WHERE booking_id = ?", id);
        em.clear();
        end(admin, id, "1050.0").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This rental has no starting odometer reading, so its "
                        + "distance cannot be billed; the final bill was not calculated"));
        em.clear();
        Booking refused = bookings.findById(id).orElseThrow();
        assertThat(refused.getStatus()).isEqualTo(BookingStatus.ACTIVE);
        assertThat(refused.getEndedAt()).isNull();
        assertThat(refused.getPendingDistanceKm()).isNull();
        assertThat(refused.getEndOdometerKm()).isNull();
        assertThat(invoices.findFirstByBooking_BookingIdAndKind(id, Invoice.Kind.FINAL)).isEmpty();
        assertThat(gateway.charges()).isEmpty();
    }

    @Test
    void theOdometerReadingsCannotChangeOnceTheBillIsFrozen() throws Exception {
        UUID id = rentalStarted150MinutesAgo("1000.0");
        gateway.declineCharges("Your card was declined.");
        end(admin, id, "1050.0").andExpect(status().isPaymentRequired());
        Booking frozen = booking(id);
        assertThat(frozen.getEndedAt()).isNotNull();
        assertThat(frozen.getEndOdometerKm()).isEqualByComparingTo("1050.0");

        end(admin, id, "1060.0").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("This rental has already ended with an ending odometer of "
                        + "1050.0 km; odometer readings cannot be changed once the final bill is calculated"));
        handover(id, "990.0").andExpect(status().isConflict());

        // Retrying the settlement, with the same reading or none, charges the same frozen bill.
        end(admin, id, "1050.0").andExpect(status().isPaymentRequired());
        end(customer, id, null).andExpect(status().isPaymentRequired());
        Booking b = booking(id);
        assertThat(b.getEndOdometerKm()).isEqualByComparingTo("1050.0");
        assertThat(b.getStartOdometerKm()).isEqualByComparingTo("1000.0");
        assertThat(b.getPendingDistanceKm()).isEqualByComparingTo("50.00");
        assertThat(b.getPendingTotalCost()).isEqualByComparingTo(frozen.getPendingTotalCost());
        assertThat(gateway.charges()).allSatisfy(c -> assertThat(c.amount()).isEqualByComparingTo("500.00"));
    }

    // ── Cancelling a booking ready for pickup ─────────────────────────────────

    @Test
    void theCustomerCancelsBeforeTheHandoverForAFullRefund() throws Exception {
        UUID id = paidBooking();

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(gateway.refunds()).singleElement()
                .satisfies(r -> assertThat(r.amount()).isEqualByComparingTo("250.00"));
        assertThat(scooterStatus(id)).isEqualTo(ScooterStatus.AVAILABLE);
        assertThat(payments.findByBooking_BookingId(id).orElseThrow().getStatus()).isNotEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void onceHandedOverOnlyAnAdminCancelsTheBooking() throws Exception {
        UUID id = paidBooking();
        handover(id, "1000.0").andExpect(status().isOk());

        mvc.perform(as(customer, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isConflict());
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.READY_FOR_PICKUP);
        assertThat(gateway.refunds()).isEmpty();

        mvc.perform(as(admin, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThat(gateway.refunds()).hasSize(1);
        start(customer, id).andExpect(status().isConflict());
        assertThat(booking(id).getActualStartTime()).isNull();
    }

    @Test
    void aStartedRentalCannotBeCancelled() throws Exception {
        UUID id = paidBooking();
        handover(id, "1000.0").andExpect(status().isOk());
        start(customer, id).andExpect(status().isOk());

        mvc.perform(as(admin, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(
                        "This rental has started and can no longer be cancelled; end it instead"));
        assertThat(booking(id).getStatus()).isEqualTo(BookingStatus.ACTIVE);
    }
}
