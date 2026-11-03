package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.MaintenanceRecord;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.MaintenanceRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.services.MaintenanceService;
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
 * The admin maintenance endpoints over HTTP, through the real security filter chain, JWT handling, service and
 * embedded PostgreSQL. Scooters and users are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class MaintenanceControllerTests extends PostgresIntegrationTest {

    private static final String MAINTENANCE = "/api/admin/maintenance";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private MaintenanceRepository records;

    @Autowired
    private MaintenanceService maintenanceService;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("workshop." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Workshop " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("W" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter(ScooterStatus status, String mileage) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("M-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("MP-" + id);
        s.setStatus(status);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setTotalMileage(new BigDecimal(mileage));
        return scooters.saveAndFlush(s);
    }

    private Scooter availableScooter() {
        return saveScooter(ScooterStatus.AVAILABLE, "100.00");
    }

    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return as(Role.ADMIN, request);
    }

    private MockHttpServletRequestBuilder as(Role role, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(saveUser(role)));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String create(Scooter scooter, boolean startNow) {
        return """
                {"scooterId": "%s", "type": "REPAIR", "description": "Brake pads", "startNow": %s}"""
                .formatted(scooter.getScooterId(), startNow);
    }

    private static String complete(String cost, Integer odometer, String notes) {
        return """
                {"cost": %s, "odometerKm": %s, "notes": %s}"""
                .formatted(cost, odometer, notes == null ? "null" : "\"" + notes + "\"");
    }

    private String createRecord(Scooter scooter, boolean startNow) throws Exception {
        String body = mvc.perform(json(admin(post(MAINTENANCE)), create(scooter, startNow)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ScooterStatus scooterStatus(Scooter scooter) {
        return scooters.findById(scooter.getScooterId()).orElseThrow().getStatus();
    }

    private MaintenanceRecord record(String id) {
        return records.findById(UUID.fromString(id)).orElseThrow();
    }

    // ── Create and start ──────────────────────────────────────────────────────

    @Test
    void createSchedulesWorkWithoutTakingTheScooterOutOfService() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(json(admin(post(MAINTENANCE)), create(scooter, false)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scooterId").value(scooter.getScooterId().toString()))
                .andExpect(jsonPath("$.type").value("REPAIR"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.description").value("Brake pads"))
                .andExpect(jsonPath("$.scheduledDate").value(LocalDate.now().toString()));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void startingWorkTakesTheScooterOutOfService() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, false);

        mvc.perform(admin(post(MAINTENANCE + "/" + id + "/start")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.MAINTENANCE);
    }

    @Test
    void createWithStartNowBeginsWorkImmediately() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(json(admin(post(MAINTENANCE)), create(scooter, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.MAINTENANCE);
    }

    @Test
    void onlyScheduledWorkCanBeStarted() throws Exception {
        String id = createRecord(availableScooter(), true);

        mvc.perform(admin(post(MAINTENANCE + "/" + id + "/start")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Only scheduled work can be started"));
    }

    @Test
    void aRentedScooterCannotGoIntoTheWorkshop() throws Exception {
        Scooter rented = saveScooter(ScooterStatus.RENTED, "100.00");
        String id = createRecord(rented, false);

        mvc.perform(admin(post(MAINTENANCE + "/" + id + "/start")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Scooter is out on a rental; start work after it is returned"));

        assertThat(scooterStatus(rented)).isEqualTo(ScooterStatus.RENTED);
    }

    @Test
    void unknownScooterOrRecordIs404() throws Exception {
        Scooter ghost = new Scooter();
        ghost.setScooterId(UUID.randomUUID());

        mvc.perform(json(admin(post(MAINTENANCE)), create(ghost, false)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Scooter not found"));
        mvc.perform(admin(post(MAINTENANCE + "/" + UUID.randomUUID() + "/start")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Maintenance record not found"));
    }

    @Test
    void createValidatesTheRequest() throws Exception {
        // A body without the primitive startNow is rejected as malformed before validation.
        mvc.perform(json(admin(post(MAINTENANCE)), """
                        {"description": " "}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
        mvc.perform(json(admin(post(MAINTENANCE)), """
                        {"description": " ", "startNow": false}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.scooterId").exists())
                .andExpect(jsonPath("$.fieldErrors.type").exists())
                .andExpect(jsonPath("$.fieldErrors.description").exists());
    }

    // ── Complete and cancel ───────────────────────────────────────────────────

    @Test
    void completingWorkReturnsTheScooterToService() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, true);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("1250.00", null, "Pads replaced")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.cost").value(1250.00))
                .andExpect(jsonPath("$.completedDate").value(LocalDate.now().toString()))
                .andExpect(jsonPath("$.odometerKm").value(100))
                .andExpect(jsonPath("$.description").value(containsString("Completion notes: Pads replaced")));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void scheduledWorkCanBeCompletedWithoutBeingStarted() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, false);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("0.00", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void cancellingWorkReturnsTheScooterToService() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, true);

        mvc.perform(admin(post(MAINTENANCE + "/" + id + "/cancel")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void closedWorkCannotBeCompletedOrCancelledAgain() throws Exception {
        String done = createRecord(availableScooter(), true);
        mvc.perform(json(admin(post(MAINTENANCE + "/" + done + "/complete")), complete("10.00", null, null)))
                .andExpect(status().isOk());
        String cancelled = createRecord(availableScooter(), true);
        mvc.perform(admin(post(MAINTENANCE + "/" + cancelled + "/cancel"))).andExpect(status().isOk());

        mvc.perform(json(admin(post(MAINTENANCE + "/" + done + "/complete")), complete("10.00", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Maintenance record is already closed"));
        mvc.perform(json(admin(post(MAINTENANCE + "/" + cancelled + "/complete")), complete("10.00", null, null)))
                .andExpect(status().isConflict());
        mvc.perform(admin(post(MAINTENANCE + "/" + done + "/cancel")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Completed work cannot be cancelled"));
    }

    @Test
    void theScooterStaysInTheWorkshopWhileOtherWorkIsInProgress() throws Exception {
        Scooter scooter = availableScooter();
        String first = createRecord(scooter, true);
        String second = createRecord(scooter, true);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + first + "/complete")), complete("100.00", null, null)))
                .andExpect(status().isOk());
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.MAINTENANCE);

        mvc.perform(admin(post(MAINTENANCE + "/" + second + "/cancel"))).andExpect(status().isOk());
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void completionCostCannotBeNegative() throws Exception {
        String id = createRecord(availableScooter(), true);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("-1.00", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.cost").exists());
    }

    // ── Odometer and mileage ──────────────────────────────────────────────────

    @Test
    void theOdometerCannotGoBackwards() throws Exception {
        Scooter scooter = saveScooter(ScooterStatus.AVAILABLE, "1500.00");
        String id = createRecord(scooter, true);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("10.00", 1499, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Odometer cannot go below the current reading of 1500"));
    }

    @Test
    void aHigherOdometerReadingUpdatesTheScooterMileage() throws Exception {
        Scooter scooter = saveScooter(ScooterStatus.AVAILABLE, "1500.00");
        String id = createRecord(scooter, true);

        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("10.00", 1620, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.odometerKm").value(1620));

        assertThat(scooters.findById(scooter.getScooterId()).orElseThrow().getTotalMileage())
                .isEqualByComparingTo("1620.00");
    }

    // ── Routine service ───────────────────────────────────────────────────────

    @Test
    void aRoutineServiceIsScheduledEvery3000Km() {
        Scooter due = saveScooter(ScooterStatus.AVAILABLE, "3000.00");
        Scooter notDue = saveScooter(ScooterStatus.AVAILABLE, "2999.00");

        maintenanceService.scheduleServiceIfDue(due);
        maintenanceService.scheduleServiceIfDue(due);
        maintenanceService.scheduleServiceIfDue(notDue);

        assertThat(records.findByScooter_ScooterIdOrderByCreatedAtDesc(due.getScooterId()))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.getType()).isEqualTo(MaintenanceRecord.Type.SERVICE);
                    assertThat(r.getStatus()).isEqualTo(MaintenanceRecord.Status.SCHEDULED);
                });
        assertThat(records.findByScooter_ScooterIdOrderByCreatedAtDesc(notDue.getScooterId())).isEmpty();
    }

    // ── Deleted scooter (current behaviour, characterized) ────────────────────

    @Test
    void workOnADeletedScooterLeavesItsStatusAlone() throws Exception {
        Scooter retired = availableScooter();
        retired.setDeleted(true);
        scooters.saveAndFlush(retired);

        mvc.perform(json(admin(post(MAINTENANCE)), create(retired, true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        assertThat(scooterStatus(retired)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    // ── Bookings hold the scooter (BE-031) ────────────────────────────────────

    @Autowired
    private BookingRepository bookings;

    /** Fixture: a booking in the given state on the scooter (the scooter's own status is left as it is). */
    private void book(Scooter scooter, BookingStatus status) {
        Booking b = new Booking();
        b.setReference("BK-MT" + unique());
        b.setUser(saveUser(Role.USER));
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(LocalDateTime.now().plusDays(1));
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setPickupLocation("Test pickup");
        bookings.saveAndFlush(b);
    }

    @Test
    void workCannotStartWhileABookingHoldsTheScooter() throws Exception {
        for (BookingStatus holding : new BookingStatus[] {BookingStatus.PENDING, BookingStatus.ACTIVE}) {
            Scooter scooter = availableScooter();
            String id = createRecord(scooter, false);
            book(scooter, holding);

            mvc.perform(admin(post(MAINTENANCE + "/" + id + "/start")))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message")
                            .value("Scooter has a pending or active booking; cancel it before starting work"));
            assertThat(scooterStatus(scooter)).as(holding.name()).isEqualTo(ScooterStatus.AVAILABLE);
        }
    }

    @Test
    void workCannotStartImmediatelyWhileABookingHoldsTheScooter() throws Exception {
        Scooter scooter = availableScooter();
        book(scooter, BookingStatus.PENDING);

        mvc.perform(json(admin(post(MAINTENANCE)), create(scooter, true)))
                .andExpect(status().isConflict());
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void finishedOrCancelledBookingsDoNotHoldTheScooter() throws Exception {
        Scooter scooter = availableScooter();
        book(scooter, BookingStatus.CANCELLED);
        String id = createRecord(scooter, false);

        mvc.perform(admin(post(MAINTENANCE + "/" + id + "/start")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"));
    }

    // ── Admin scooter status (BE-031) ─────────────────────────────────────────

    private MockHttpServletRequestBuilder setStatus(Scooter scooter, ScooterStatus value) {
        return admin(patch("/api/admin/scooters/" + scooter.getScooterId() + "/status")
                .param("value", value.name()));
    }

    @Test
    void statusCannotBeSetToMaintenanceWhileABookingHoldsTheScooter() throws Exception {
        Scooter scooter = availableScooter();
        book(scooter, BookingStatus.PENDING);

        mvc.perform(setStatus(scooter, ScooterStatus.MAINTENANCE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Scooter has a pending or active booking; cancel it before maintenance"));
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }

    @Test
    void statusCannotBeSetToAvailableWhileWorkIsInProgress() throws Exception {
        Scooter scooter = availableScooter();
        createRecord(scooter, true);

        mvc.perform(setStatus(scooter, ScooterStatus.AVAILABLE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message")
                        .value("Scooter has maintenance in progress; complete or cancel it first"));
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.MAINTENANCE);
    }

    @Test
    void validStatusChangesStillWork() throws Exception {
        Scooter scooter = availableScooter();

        mvc.perform(setStatus(scooter, ScooterStatus.MAINTENANCE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("MAINTENANCE"));
        mvc.perform(setStatus(scooter, ScooterStatus.AVAILABLE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"));
        // Once the work is closed the scooter can be returned by hand too.
        String id = createRecord(scooter, true);
        mvc.perform(json(admin(post(MAINTENANCE + "/" + id + "/complete")), complete("10.00", null, null)))
                .andExpect(status().isOk());
        mvc.perform(setStatus(scooter, ScooterStatus.MAINTENANCE)).andExpect(status().isOk());
        mvc.perform(setStatus(scooter, ScooterStatus.AVAILABLE)).andExpect(status().isOk());
    }

    @Test
    void rentedRulesOfTheStatusEndpointAreUnchanged() throws Exception {
        Scooter rented = saveScooter(ScooterStatus.RENTED, "100.00");

        mvc.perform(setStatus(rented, ScooterStatus.AVAILABLE)).andExpect(status().isConflict());
        mvc.perform(setStatus(availableScooter(), ScooterStatus.RENTED)).andExpect(status().isBadRequest());
        mvc.perform(as(Role.USER, patch("/api/admin/scooters/" + rented.getScooterId() + "/status")
                        .param("value", "AVAILABLE")))
                .andExpect(status().isForbidden());
    }

    // ── Access ────────────────────────────────────────────────────────────────

    @Test
    void adminsAndSuperAdminsListWork() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, false);

        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            mvc.perform(as(role, get(MAINTENANCE).param("scooterId", scooter.getScooterId().toString())))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].id").value(hasItem(id)));
        }
    }

    @Test
    void customersAndAnonymousUsersCannotUseMaintenance() throws Exception {
        Scooter scooter = availableScooter();
        String id = createRecord(scooter, false);

        mvc.perform(as(Role.USER, get(MAINTENANCE))).andExpect(status().isForbidden());
        mvc.perform(json(as(Role.USER, post(MAINTENANCE)), create(scooter, true))).andExpect(status().isForbidden());
        mvc.perform(as(Role.USER, post(MAINTENANCE + "/" + id + "/start"))).andExpect(status().isForbidden());
        mvc.perform(get(MAINTENANCE)).andExpect(status().isUnauthorized());
        mvc.perform(json(post(MAINTENANCE), create(scooter, true))).andExpect(status().isUnauthorized());
        mvc.perform(post(MAINTENANCE + "/" + id + "/cancel")).andExpect(status().isUnauthorized());

        assertThat(record(id).getStatus()).isEqualTo(MaintenanceRecord.Status.SCHEDULED);
        assertThat(scooterStatus(scooter)).isEqualTo(ScooterStatus.AVAILABLE);
    }
}
