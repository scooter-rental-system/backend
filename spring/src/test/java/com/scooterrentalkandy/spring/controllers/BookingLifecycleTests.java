package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.scooterrentalkandy.spring.entity.Contract;
import com.scooterrentalkandy.spring.entity.GearItem;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Category;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ContractRepository;
import com.scooterrentalkandy.spring.repository.GearItemRepository;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.PaymentRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.Rentals;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract view and signing, cancelling PENDING bookings, status conflicts and the admin booking list over HTTP,
 * through the real security filter chain, JWT handling, services and embedded PostgreSQL.
 *
 * <p>Only transitions that need neither payment nor GPS are exercised. COMPLETED and ACTIVE bookings used as
 * starting points are fixture state persisted directly, not the result of a tested transition. Each test rolls
 * back.
 */
@AutoConfigureMockMvc
@Transactional
class BookingLifecycleTests extends PostgresIntegrationTest {

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
    private ContractRepository contracts;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("lifecycle." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Lifecycle Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("L" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter availableScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("L-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("LP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private GearItem saveGear(int quantity) {
        GearItem g = new GearItem();
        g.setName("Test Tent " + unique());
        g.setCategory(Category.CAMPING);
        g.setDailyRate(new BigDecimal("500.00"));
        g.setTotalQuantity(quantity);
        return gearItems.saveAndFlush(g);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bookingRequest(UUID scooterId, UUID gearId) {
        String gear = gearId == null ? "" : """
                , "gear": [{"gearItemId": "%s", "quantity": 1}]""".formatted(gearId);
        return """
                {"scooterId": "%s", "startTime": "%s", "pickupLocation": "Kandy Lake Round"%s}"""
                .formatted(scooterId, LocalDateTime.now().plusDays(2).truncatedTo(ChronoUnit.MINUTES), gear);
    }

    /** A PENDING booking created through the API. */
    private UUID book(User customer, Scooter scooter) throws Exception {
        return book(customer, scooter, null);
    }

    private UUID book(User customer, Scooter scooter, UUID gearId) throws Exception {
        String body = mvc.perform(json(as(customer, post("/api/bookings")),
                        bookingRequest(scooter.getScooterId(), gearId)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.id"));
    }

    /** Fixture only: a booking stored directly in the given state, not reached through a tested transition. */
    private Booking fixture(User customer, BookingStatus status) {
        Booking b = new Booking();
        b.setReference("BK-F" + unique());
        b.setUser(customer);
        b.setScooter(availableScooter());
        b.setStatus(status);
        b.setStartTime(LocalDateTime.now().minusHours(3));
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setPickupLocation("Test pickup");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(LocalDateTime.now().minusHours(1));
            b.setDistanceKm(new BigDecimal("12.00"));
            b.setTotalCost(new BigDecimal("680.00"));
            b.setContractSigned(true);
        }
        return bookings.saveAndFlush(b);
    }

    private static String sign(String name, boolean agree) {
        return """
                {"signedName": "%s", "agree": %s}""".formatted(name, agree);
    }

    // ── Contract view ─────────────────────────────────────────────────────────

    @Test
    void ownerAndAdminsCanViewTheContract() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());

        for (User viewer : new User[] {owner, saveUser(Role.ADMIN), saveUser(Role.SUPER_ADMIN)}) {
            mvc.perform(as(viewer, get("/api/bookings/" + id + "/contract")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.contractNumber").value(startsWith("CT")))
                    .andExpect(jsonPath("$.termsText").value(containsString(owner.getFullName())))
                    .andExpect(jsonPath("$.signed").value(false))
                    .andExpect(jsonPath("$.signedIp").doesNotExist())
                    .andExpect(jsonPath("$.termsText").value(not(containsString(
                            owner.getNicNumber()))));
        }
    }

    @Test
    void anotherCustomerCannotSeeTheContract() throws Exception {
        UUID id = book(saveUser(Role.USER), availableScooter());

        mvc.perform(as(saveUser(Role.USER), get("/api/bookings/" + id + "/contract")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Booking not found"));
        mvc.perform(get("/api/bookings/" + id + "/contract")).andExpect(status().isUnauthorized());
    }

    // ── Contract signing ──────────────────────────────────────────────────────

    @Test
    void ownerSignsTheContract() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());

        mvc.perform(json(as(owner, post("/api/bookings/" + id + "/contract/sign")),
                        sign(owner.getFullName().toUpperCase(), true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.signedName").value(owner.getFullName().toUpperCase()))
                .andExpect(jsonPath("$.signedAt").isString());

        assertThat(bookings.findById(id).orElseThrow().isContractSigned()).isTrue();
        Contract contract = contracts.findByBooking_BookingId(id).orElseThrow();
        assertThat(contract.getSignedAt()).isNotNull();
        assertThat(contract.getSignedIp()).isNotBlank();
        mvc.perform(as(owner, get("/api/bookings/" + id))).andExpect(jsonPath("$.contractSigned").value(true));
    }

    @Test
    void signingRequiresAgreementAndTheAccountName() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());
        String path = "/api/bookings/" + id + "/contract/sign";

        mvc.perform(json(as(owner, post(path)), sign(owner.getFullName(), false)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("You must agree to the rental terms"));
        mvc.perform(json(as(owner, post(path)), sign("Someone Else", true)))
                .andExpect(status().isBadRequest());
        mvc.perform(json(as(owner, post(path)), sign(" ", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.signedName").exists());
        mvc.perform(json(as(owner, post(path)), "{}"))
                .andExpect(status().isBadRequest());

        assertThat(bookings.findById(id).orElseThrow().isContractSigned()).isFalse();
    }

    @Test
    void onlyTheOwnerCanSign() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());
        String path = "/api/bookings/" + id + "/contract/sign";

        mvc.perform(json(as(saveUser(Role.USER), post(path)), sign(owner.getFullName(), true)))
                .andExpect(status().isNotFound());
        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(json(as(saveUser(role), post(path)), sign(owner.getFullName(), true)))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(json(post(path), sign(owner.getFullName(), true))).andExpect(status().isUnauthorized());

        assertThat(bookings.findById(id).orElseThrow().isContractSigned()).isFalse();
    }

    /** Current behaviour: signing again is idempotent and keeps the first signature. */
    @Test
    void signingTwiceKeepsTheFirstSignature() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());
        String path = "/api/bookings/" + id + "/contract/sign";
        mvc.perform(json(as(owner, post(path)), sign(owner.getFullName(), true))).andExpect(status().isOk());
        LocalDateTime firstSignedAt = contracts.findByBooking_BookingId(id).orElseThrow().getSignedAt();

        mvc.perform(json(as(owner, post(path)), sign("Different Name", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.signed").value(true))
                .andExpect(jsonPath("$.signedName").value(owner.getFullName()));
        assertThat(contracts.findByBooking_BookingId(id).orElseThrow().getSignedAt()).isEqualTo(firstSignedAt);
    }

    @Test
    void aCancelledBookingsContractCannotBeSigned() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());
        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        mvc.perform(json(as(owner, post("/api/bookings/" + id + "/contract/sign")), sign(owner.getFullName(), true)))
                .andExpect(status().isConflict());
    }

    // ── Cancelling a PENDING booking ──────────────────────────────────────────

    @Test
    void ownerCancelsAPendingBookingAndTheScooterAndGearAreFreed() throws Exception {
        User owner = saveUser(Role.USER);
        Scooter scooter = availableScooter();
        GearItem onlyTent = saveGear(1);
        UUID id = book(owner, scooter, onlyTent.getGearItemId());

        // While PENDING, the scooter and the only tent are held.
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")),
                        bookingRequest(scooter.getScooterId(), null)))
                .andExpect(status().isConflict());
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")),
                        bookingRequest(availableScooter().getScooterId(), onlyTent.getGearItemId())))
                .andExpect(status().isConflict());

        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.stage").value("CANCELLED"))
                .andExpect(jsonPath("$.cancelledAt").isString());

        Booking cancelled = bookings.findById(id).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(BookingStatus.CANCELLED);
        assertThat(cancelled.getCancelledAt()).isNotNull();
        // No payment existed, so no payment provider was involved, and a PENDING cancel sends no notification.
        assertThat(payments.findByBooking_BookingId(id)).isEmpty();
        assertThat(notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(owner.getUserId(),
                Notification.Channel.IN_APP)).isEmpty();

        // The scooter and the tent can be booked again.
        mvc.perform(json(as(saveUser(Role.USER), post("/api/bookings")),
                        bookingRequest(scooter.getScooterId(), onlyTent.getGearItemId())))
                .andExpect(status().isCreated());
    }

    @Test
    void adminsCanCancelACustomersPendingBooking() throws Exception {
        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            UUID id = book(saveUser(Role.USER), availableScooter());

            mvc.perform(as(saveUser(role), post("/api/bookings/" + id + "/cancel")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.CANCELLED);
        }
    }

    @Test
    void anotherCustomerCannotCancel() throws Exception {
        UUID id = book(saveUser(Role.USER), availableScooter());

        mvc.perform(as(saveUser(Role.USER), post("/api/bookings/" + id + "/cancel")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/bookings/" + id + "/cancel")).andExpect(status().isUnauthorized());
        assertThat(bookings.findById(id).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    // ── Status conflicts ──────────────────────────────────────────────────────

    @Test
    void cancellingTwiceIs409() throws Exception {
        User owner = saveUser(Role.USER);
        UUID id = book(owner, availableScooter());
        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel"))).andExpect(status().isOk());

        mvc.perform(as(owner, post("/api/bookings/" + id + "/cancel")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A booking that is CANCELLED cannot be cancelled"));
    }

    @Test
    void aCompletedBookingCannotBeCancelled() throws Exception {
        User owner = saveUser(Role.USER);
        Booking completed = fixture(owner, BookingStatus.COMPLETED);

        mvc.perform(as(owner, post("/api/bookings/" + completed.getBookingId() + "/cancel")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("A booking that is COMPLETED cannot be cancelled"));
        assertThat(bookings.findById(completed.getBookingId()).orElseThrow().getStatus())
                .isEqualTo(BookingStatus.COMPLETED);
    }

    /**
     * Ending a rental needs the ending odometer and a payment charge, so only the refusals that happen before either is
     * reached are covered here.
     */
    @Test
    void onlyAnActiveRentalCanBeEnded() throws Exception {
        User owner = saveUser(Role.USER);
        UUID pending = book(owner, availableScooter());
        Booking completed = fixture(owner, BookingStatus.COMPLETED);

        mvc.perform(as(owner, put("/api/bookings/" + pending + "/complete")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only an active rental can be ended (status PENDING)"));
        mvc.perform(as(owner, put("/api/bookings/" + completed.getBookingId() + "/complete")))
                .andExpect(status().isConflict());
        mvc.perform(as(saveUser(Role.USER), put("/api/bookings/" + pending + "/complete")))
                .andExpect(status().isNotFound());
        mvc.perform(put("/api/bookings/" + pending + "/complete")).andExpect(status().isUnauthorized());
        assertThat(bookings.findById(pending).orElseThrow().getStatus()).isEqualTo(BookingStatus.PENDING);
    }

    // ── Admin booking list ────────────────────────────────────────────────────

    @Test
    void adminsListEveryCustomersBookings() throws Exception {
        UUID first = book(saveUser(Role.USER), availableScooter());
        UUID second = book(saveUser(Role.USER), availableScooter());

        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(as(saveUser(role), get("/api/admin/bookings")))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id").value(hasItems(first.toString(), second.toString())));
        }
    }

    @Test
    void adminListFiltersByStatus() throws Exception {
        User owner = saveUser(Role.USER);
        UUID pending = book(owner, availableScooter());
        UUID cancelled = book(owner, availableScooter());
        mvc.perform(as(owner, post("/api/bookings/" + cancelled + "/cancel"))).andExpect(status().isOk());
        User admin = saveUser(Role.ADMIN);

        mvc.perform(as(admin, get("/api/admin/bookings").param("status", "PENDING")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status").value(everyItem(is("PENDING"))))
                .andExpect(jsonPath("$[*].id").value(hasItem(pending.toString())))
                .andExpect(jsonPath("$[*].id").value(not(hasItem(cancelled.toString()))));
        mvc.perform(as(admin, get("/api/admin/bookings").param("status", "CANCELLED")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].status").value(everyItem(is("CANCELLED"))))
                .andExpect(jsonPath("$[*].id").value(hasItem(cancelled.toString())));
    }

    @Test
    void anUnknownStatusFilterIs400() throws Exception {
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/bookings").param("status", "BOGUS")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid value for 'status'"));
    }

    @Test
    void customersAndAnonymousUsersCannotListAllBookings() throws Exception {
        mvc.perform(as(saveUser(Role.USER), get("/api/admin/bookings"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/bookings")).andExpect(status().isUnauthorized());
    }
}
