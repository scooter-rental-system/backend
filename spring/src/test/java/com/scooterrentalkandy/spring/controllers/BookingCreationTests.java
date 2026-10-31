package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
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
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Booking creation, ownership, availability, date rules and server-side pricing over HTTP, through the real
 * security filter chain, JWT handling, services and embedded PostgreSQL. No payment or GPS path is exercised.
 * Users, scooters and gear are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class BookingCreationTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private GearItemRepository gearItems;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("booker." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Booker " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("B" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter(ScooterStatus status, boolean deleted) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("T-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("TP-" + id);
        s.setStatus(status);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setDeleted(deleted);
        return scooters.saveAndFlush(s);
    }

    private Scooter availableScooter() {
        return saveScooter(ScooterStatus.AVAILABLE, false);
    }

    private GearItem saveGear(String dailyRate, int quantity) {
        GearItem g = new GearItem();
        g.setName("Test Helmet " + unique());
        g.setCategory(Category.SAFETY);
        g.setDailyRate(new BigDecimal(dailyRate));
        g.setTotalQuantity(quantity);
        return gearItems.saveAndFlush(g);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    /** Well clear of the "not in the past" and "at most 365 days ahead" boundaries. */
    private static LocalDateTime inDays(long days) {
        return LocalDateTime.now().plusDays(days).truncatedTo(ChronoUnit.MINUTES);
    }

    private static String booking(UUID scooterId, LocalDateTime start) {
        return """
                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"}"""
                .formatted(scooterId, start);
    }

    private String createBooking(User user, Scooter scooter) throws Exception {
        String body = mvc.perform(json(as(user, post("/api/bookings")), booking(scooter.getScooterId(), inDays(2))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    // ── Create ────────────────────────────────────────────────────────────────

    @Test
    void customerCreatesAPendingBookingForThemselves() throws Exception {
        User customer = saveUser(Role.USER);
        Scooter scooter = availableScooter();
        LocalDateTime start = inDays(2);

        String body = mvc.perform(json(as(customer, post("/api/bookings")), booking(scooter.getScooterId(), start)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.stage").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.customer.id").value(customer.getUserId().toString()))
                .andExpect(jsonPath("$.scooter.id").value(scooter.getScooterId().toString()))
                .andExpect(jsonPath("$.contractSigned").value(false))
                .andExpect(jsonPath("$.payment").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Booking saved = bookings.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(BookingStatus.PENDING);
        assertThat(saved.getUser().getUserId()).isEqualTo(customer.getUserId());
        assertThat(saved.getScooter().getScooterId()).isEqualTo(scooter.getScooterId());
        assertThat(saved.getStartTime()).isEqualTo(start);
        assertThat(saved.getReference()).startsWith("BK");
        assertThat(saved.getTotalCost()).isNull();
        // Booking alone does not take the scooter out of the fleet; payment activation does.
        assertThat(scooters.findById(scooter.getScooterId()).orElseThrow().getStatus())
                .isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void theCustomerComesFromTheTokenNotTheRequest() throws Exception {
        User customer = saveUser(Role.USER);
        User victim = saveUser(Role.USER);
        Scooter scooter = availableScooter();
        String body = booking(scooter.getScooterId(), inDays(2)).replace("{", """
                {"userId": "%s", "customerId": "%s", "user": {"id": "%s"}, "status": "ACTIVE",\s"""
                .formatted(victim.getUserId(), victim.getUserId(), victim.getUserId()));

        mvc.perform(json(as(customer, post("/api/bookings")), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customer.id").value(customer.getUserId().toString()))
                .andExpect(jsonPath("$.status").value("PENDING"));
        assertThat(bookings.findByUser_UserIdOrderByCreatedAtDesc(victim.getUserId())).isEmpty();
    }

    @Test
    void requestsWithoutATokenAre401() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(json(post("/api/bookings"), booking(scooter.getScooterId(), inDays(2))))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/bookings/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        assertThat(bookings.existsByScooter_ScooterIdAndStatusIn(scooter.getScooterId(), BookingStatus.BLOCKING))
                .isFalse();
    }

    @Test
    void createValidatesTheRequest() throws Exception {
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), """
                        {"pickupLocation": " "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.scooterId").exists())
                .andExpect(jsonPath("$.fieldErrors.startTime").exists())
                .andExpect(jsonPath("$.fieldErrors.pickupLocation").exists());
    }

    // ── List and view ─────────────────────────────────────────────────────────

    @Test
    void customersSeeOnlyTheirOwnBookings() throws Exception {
        User alice = saveUser(Role.USER);
        User bob = saveUser(Role.USER);
        String aliceBooking = createBooking(alice, availableScooter());
        String bobBooking = createBooking(bob, availableScooter());

        mvc.perform(as(alice, get("/api/bookings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(aliceBooking)));
        mvc.perform(as(bob, get("/api/bookings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(contains(bobBooking)))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(aliceBooking))));
    }

    @Test
    void ownerCanViewTheirBooking() throws Exception {
        User owner = saveUser(Role.USER);
        String id = createBooking(owner, availableScooter());

        mvc.perform(as(owner, get("/api/bookings/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.customer.id").value(owner.getUserId().toString()));
    }

    @Test
    void anotherCustomersBookingLooksLikeItDoesNotExist() throws Exception {
        String id = createBooking(saveUser(Role.USER), availableScooter());
        User stranger = saveUser(Role.USER);

        String someoneElses = mvc.perform(as(stranger, get("/api/bookings/" + id)))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missing = mvc.perform(as(stranger, get("/api/bookings/" + UUID.randomUUID())))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        String withoutTimestamp = "\"timestamp\":\"[^\"]*\"";
        assertThat(someoneElses.replaceAll(withoutTimestamp, "")).isEqualTo(missing.replaceAll(withoutTimestamp, ""));
    }

    @Test
    void adminsCanViewAnyBooking() throws Exception {
        User owner = saveUser(Role.USER);
        String id = createBooking(owner, availableScooter());

        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(as(saveUser(role), get("/api/bookings/" + id)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.customer.id").value(owner.getUserId().toString()));
        }
    }

    // ── Availability ──────────────────────────────────────────────────────────

    @Test
    void unknownScooterIs404() throws Exception {
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), booking(UUID.randomUUID(), inDays(2))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Scooter not found"));
    }

    @Test
    void deletedScooterIsRejected() throws Exception {
        Scooter retired = saveScooter(ScooterStatus.AVAILABLE, true);

        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), booking(retired.getScooterId(), inDays(2))))
                .andExpect(status().isBadRequest());
        assertThat(bookings.existsByScooter_ScooterIdAndStatusIn(retired.getScooterId(), BookingStatus.BLOCKING))
                .isFalse();
    }

    @Test
    void scootersInMaintenanceOrOutOnRentAreRejected() throws Exception {
        User customer = saveUser(Role.USER);
        for (ScooterStatus unavailable : new ScooterStatus[] {ScooterStatus.MAINTENANCE, ScooterStatus.RENTED}) {
            Scooter scooter = saveScooter(unavailable, false);

            mvc.perform(json(as(customer, post("/api/bookings")), booking(scooter.getScooterId(), inDays(2))))
                    .andExpect(status().isConflict());
            assertThat(bookings.existsByScooter_ScooterIdAndStatusIn(scooter.getScooterId(), BookingStatus.BLOCKING))
                    .isFalse();
        }
    }

    @Test
    void aScooterWithAPendingBookingCannotBeBookedAgain() throws Exception {
        Scooter scooter = availableScooter();
        createBooking(saveUser(Role.USER), scooter);

        for (User next : new User[] {saveUser(Role.USER), saveUser(Role.USER)}) {
            mvc.perform(json(as(next, post("/api/bookings")), booking(scooter.getScooterId(), inDays(3))))
                    .andExpect(status().isConflict());
        }
        assertThat(bookings.findAll().stream().filter(b -> b.getScooter().getScooterId().equals(scooter.getScooterId())))
                .hasSize(1);
    }

    @Test
    void aScooterWithAnActiveBookingCannotBeBooked() throws Exception {
        // An ACTIVE booking on a scooter whose own status still says AVAILABLE: the booking check alone decides.
        Scooter scooter = availableScooter();
        Booking active = new Booking();
        active.setReference("BK-T" + unique());
        active.setUser(saveUser(Role.USER));
        active.setScooter(scooter);
        active.setStatus(BookingStatus.ACTIVE);
        active.setStartTime(LocalDateTime.now().minusHours(1));
        active.setActualStartTime(active.getStartTime());
        active.setPickupLocation("Test pickup");
        bookings.saveAndFlush(active);

        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), booking(scooter.getScooterId(), inDays(2))))
                .andExpect(status().isConflict());
    }

    // ── Dates ─────────────────────────────────────────────────────────────────

    @Test
    void startTimeInThePastIsRejected() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), booking(scooter.getScooterId(), inDays(-1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Start time cannot be in the past"));
    }

    @Test
    void startTimeMoreThan365DaysAheadIsRejected() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), booking(scooter.getScooterId(), inDays(400))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Bookings open up to 365 days ahead"));
    }

    @Test
    void startTimeWithinTheBookingWindowIsAccepted() throws Exception {
        User customer = saveUser(Role.USER);
        for (long days : new long[] {1, 364}) {
            Scooter scooter = availableScooter();
            LocalDateTime start = inDays(days);
            String body = mvc.perform(json(as(customer, post("/api/bookings")), booking(scooter.getScooterId(), start)))
                    .andExpect(status().isCreated())
                    .andReturn().getResponse().getContentAsString();
            assertThat(bookings.findById(UUID.fromString(JsonPath.read(body, "$.id"))).orElseThrow().getStartTime())
                    .isEqualTo(start);
        }
    }

    // ── Inactive account (BE-027) ─────────────────────────────────────────────

    @Test
    void deactivatedCustomerCannotBookWithAnExistingToken() throws Exception {
        User customer = saveUser(Role.USER);
        Scooter scooter = availableScooter();
        String token = jwt.issue(customer);
        mvc.perform(get("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        customer.setIsActive(false);
        users.saveAndFlush(customer);

        mvc.perform(json(post("/api/bookings").header(HttpHeaders.AUTHORIZATION, "Bearer " + token),
                        booking(scooter.getScooterId(), inDays(2))))
                .andExpect(status().isUnauthorized());
        assertThat(bookings.existsByScooter_ScooterIdAndStatusIn(scooter.getScooterId(), BookingStatus.BLOCKING))
                .isFalse();
    }

    // ── Server-side pricing ───────────────────────────────────────────────────

    @Test
    void quoteUsesTheStoredRates() throws Exception {
        Scooter scooter = availableScooter();
        GearItem helmet = saveGear("120.00", 5);

        // Initial charge = 1 hour of the scooter + 1 day of each gear line: 250.00 + 2 x 120.00 = 490.00.
        mvc.perform(json(post("/api/bookings/quote"), """
                        {"scooterId": "%s", "gear": [{"gearItemId": "%s", "quantity": 2}],
                         "hourlyRate": 1, "initialCharge": 1}""".formatted(scooter.getScooterId(), helmet.getGearItemId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hourlyRate").value(250.00))
                .andExpect(jsonPath("$.perKmRate").value(15.00))
                .andExpect(jsonPath("$.gearPerDay").value(240.00))
                .andExpect(jsonPath("$.gear[0].quantity").value(2))
                .andExpect(jsonPath("$.initialCharge").value(490.00));
    }

    @Test
    void bookingAmountsComeFromTheServerNotTheRequest() throws Exception {
        Scooter scooter = availableScooter();
        GearItem helmet = saveGear("120.00", 5);
        String body = """
                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round",
                 "gear": [{"gearItemId": "%s", "quantity": 1}],
                 "initialCharge": 1, "totalCost": 1, "hourlyRate": 1, "perKmRate": 0}"""
                .formatted(scooter.getScooterId(), inDays(2), helmet.getGearItemId());

        String response = mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.initialCharge").value(370.00))
                .andExpect(jsonPath("$.scooter.hourlyRate").value(250.00))
                .andExpect(jsonPath("$.scooter.perKmRate").value(15.00))
                .andExpect(jsonPath("$.gear[0].dailyRate").value(120.00))
                .andExpect(jsonPath("$.totalCost").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        Booking saved = bookings.findById(UUID.fromString(JsonPath.read(response, "$.id"))).orElseThrow();
        assertThat(saved.getTotalCost()).isNull();
        assertThat(saved.getPendingTotalCost()).isNull();
    }

    @Test
    void quoteForAnUnknownScooterIs404() throws Exception {
        mvc.perform(json(post("/api/bookings/quote"), """
                        {"scooterId": "%s"}""".formatted(UUID.randomUUID())))
                .andExpect(status().isNotFound());
    }
}
