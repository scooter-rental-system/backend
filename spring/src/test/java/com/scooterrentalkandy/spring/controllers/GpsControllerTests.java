package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.GpsPing;
import com.scooterrentalkandy.spring.entity.Notification;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.SpeedViolation;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.GpsPingRepository;
import com.scooterrentalkandy.spring.repository.NotificationRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.SpeedViolationRepository;
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
 * The GPS API over HTTP, through the real security filter chain, JWT handling, service and embedded PostgreSQL.
 * Coordinates are test fixtures posted exactly as a device or browser would post real ones. Each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class GpsControllerTests extends PostgresIntegrationTest {

    /** The test profile's device key (application-test.yml); not a real credential. */
    private static final String DEVICE_KEY = "test-device-key-not-a-secret";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private GpsPingRepository pings;

    @Autowired
    private SpeedViolationRepository violations;

    @Autowired
    private NotificationRepository notifications;

    @Autowired
    private JwtService jwt;

    private final LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("gpsapi." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Gps Api " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("G" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private Scooter saveScooter() {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("GA-" + id);
        s.setModel("Test Scooter");
        s.setPlateNumber("GAP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        return scooters.saveAndFlush(s);
    }

    private Booking booking(User customer, Scooter scooter, BookingStatus status, LocalDateTime start) {
        Booking b = new Booking();
        b.setReference("BK-GA" + unique());
        b.setUser(customer);
        b.setScooter(scooter);
        b.setStatus(status);
        b.setStartTime(start);
        if (status == BookingStatus.ACTIVE || status == BookingStatus.COMPLETED) {
            Rentals.startedAt(b, b.getStartTime()); // a started rental has its actual start
        }
        b.setPickupLocation("Test pickup");
        if (status == BookingStatus.COMPLETED) {
            b.setEndTime(start.plusHours(1));
            b.setDistanceKm(new BigDecimal("1.00"));
            b.setTotalCost(new BigDecimal("265.00"));
        }
        if (status == BookingStatus.CANCELLED) {
            b.setCancelledAt(start.minusMinutes(5));
        }
        return bookings.saveAndFlush(b);
    }

    private GpsPing storedPing(Booking b, String lat, String lng, LocalDateTime at) {
        GpsPing p = new GpsPing();
        p.setScooter(b.getScooter());
        p.setBooking(b);
        p.setLatitude(new BigDecimal(lat));
        p.setLongitude(new BigDecimal(lng));
        p.setSpeedKmh(new BigDecimal("12.00"));
        p.setRecordedAt(at);
        return pings.saveAndFlush(p);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String devicePing(Scooter s, String lat, String lng, String speed, LocalDateTime at) {
        return """
                {"scooterCode": "%s", "latitude": %s, "longitude": %s, "speedKmh": %s%s}"""
                .formatted(s.getCode(), lat, lng, speed, at == null ? "" : ", \"recordedAt\": \"" + at + "\"");
    }

    private static MockHttpServletRequestBuilder fromDevice(String key, String body) {
        MockHttpServletRequestBuilder request = json(post("/api/gps/pings"), body);
        return key == null ? request : request.header("X-Device-Key", key);
    }

    private List<GpsPing> logOf(Booking b) {
        return pings.findByBooking_BookingIdOrderByRecordedAtAsc(b.getBookingId());
    }

    // ── Device reports ────────────────────────────────────────────────────────

    @Test
    void aDeviceWithTheKeyReportsDuringARental() throws Exception {
        Scooter scooter = saveScooter();
        Booking rental = booking(saveUser(Role.USER), scooter, BookingStatus.ACTIVE, now.minusHours(1));

        mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, "7.291000", "80.632000", "22.5", now)))
                .andExpect(status().isAccepted());

        assertThat(logOf(rental)).singleElement().satisfies(p -> {
            assertThat(p.getLatitude()).isEqualByComparingTo("7.291000");
            assertThat(p.getSpeedKmh()).isEqualByComparingTo("22.50");
            assertThat(p.getRecordedAt()).isEqualTo(now);
        });
    }

    @Test
    void aDeviceWithoutTheRightKeyIs401() throws Exception {
        Scooter scooter = saveScooter();
        Booking rental = booking(saveUser(Role.USER), scooter, BookingStatus.ACTIVE, now.minusHours(1));
        String body = devicePing(scooter, "7.291000", "80.632000", "10", now);

        mvc.perform(fromDevice(null, body)).andExpect(status().isUnauthorized());
        mvc.perform(fromDevice("wrong-key", body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid device key"));
        // The key is checked before the body: an invalid body without the key is still 401.
        mvc.perform(fromDevice("wrong-key", "{}")).andExpect(status().isUnauthorized());
        assertThat(logOf(rental)).isEmpty();
    }

    @Test
    void aDeviceTimeMoreThanTwoMinutesAheadIsRefused() throws Exception {
        Scooter scooter = saveScooter();
        Booking rental = booking(saveUser(Role.USER), scooter, BookingStatus.ACTIVE, now.minusHours(1));

        mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, "7.29", "80.63", "10", now.plusMinutes(3))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("recordedAt is in the future"));
        mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, "7.29", "80.63", "10", now.plusMinutes(1))))
                .andExpect(status().isAccepted());
        // A buffered older reading is accepted too.
        mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, "7.28", "80.62", "10", now.minusMinutes(20))))
                .andExpect(status().isAccepted());
        assertThat(logOf(rental)).hasSize(2);
    }

    @Test
    void impossibleDeviceReportsAre400() throws Exception {
        Scooter scooter = saveScooter();
        for (String[] bad : new String[][] {{"90.5", "80.63", "10"}, {"7.29", "-180.1", "10"}, {"7.29", "80.63", "-1"},
                {"7.29", "80.63", "1000"}}) {
            mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, bad[0], bad[1], bad[2], null)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Invalid ping: ")));
        }
        mvc.perform(fromDevice(DEVICE_KEY, """
                        {"latitude": 7.29, "longitude": 80.63, "speedKmh": 10}"""))
                .andExpect(status().isBadRequest());
        assertThat(scooters.findById(scooter.getScooterId()).orElseThrow().getLastSeenAt()).isNull();
    }

    // ── The rider's own browser/device ────────────────────────────────────────

    private static String location(String lat, String lng, String extra) {
        return """
                {"latitude": %s, "longitude": %s%s}""".formatted(lat, lng, extra == null ? "" : ", " + extra);
    }

    @Test
    void theRiderReportsTheirScooterDuringTheirRental() throws Exception {
        User rider = saveUser(Role.USER);
        Scooter scooter = saveScooter();
        Booking rental = booking(rider, scooter, BookingStatus.ACTIVE, now.minusHours(1));
        Scooter other = saveScooter();

        // Fields naming another user, scooter or time are ignored: the scooter comes from the booking and
        // the time is the server's.
        mvc.perform(json(as(rider, post("/api/bookings/" + rental.getBookingId() + "/location")),
                        location("7.292000", "80.633000", """
                                "speedKmh": 14.5, "accuracyMeters": 8, "scooterId": "%s", "userId": "%s",
                                "recordedAt": "2000-01-01T00:00:00\"""".formatted(other.getScooterId(), UUID.randomUUID()))))
                .andExpect(status().isAccepted());

        assertThat(logOf(rental)).singleElement().satisfies(p -> {
            assertThat(p.getScooter().getScooterId()).isEqualTo(scooter.getScooterId());
            assertThat(p.getSpeedKmh()).isEqualByComparingTo("14.50");
            assertThat(Duration.between(now, p.getRecordedAt()).abs()).isLessThan(Duration.ofSeconds(30));
        });
        assertThat(scooters.findById(other.getScooterId()).orElseThrow().getLastSeenAt()).isNull();
    }

    @Test
    void withoutASpeedTheFirstReportIsZeroAndLaterOnesAreDerived() throws Exception {
        User rider = saveUser(Role.USER);
        Booking first = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        mvc.perform(json(as(rider, post("/api/bookings/" + first.getBookingId() + "/location")),
                        location("7.290000", "80.630000", null)))
                .andExpect(status().isAccepted());
        assertThat(logOf(first)).singleElement()
                .satisfies(p -> assertThat(p.getSpeedKmh()).isEqualByComparingTo("0"));

        // A previous real report a minute ago, 0.01 degrees of latitude (about 1.11 km) away: about 66.7 km/h.
        Booking second = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        LocalDateTime minuteAgo = LocalDateTime.now().minusSeconds(60);
        storedPing(second, "7.280000", "80.630000", minuteAgo);
        mvc.perform(json(as(rider, post("/api/bookings/" + second.getBookingId() + "/location")),
                        location("7.290000", "80.630000", null)))
                .andExpect(status().isAccepted());
        assertThat(logOf(second)).hasSize(2).last()
                .satisfies(p -> assertThat(p.getSpeedKmh().doubleValue()).isCloseTo(66.7, within(1.5)));
    }

    @Test
    void reportsCloserThanFiveSecondsAreIgnored() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        storedPing(rental, "7.280000", "80.630000", LocalDateTime.now().minusSeconds(2));

        mvc.perform(json(as(rider, post("/api/bookings/" + rental.getBookingId() + "/location")),
                        location("7.290000", "80.630000", null)))
                .andExpect(status().isAccepted());

        assertThat(logOf(rental)).hasSize(1);
    }

    @Test
    void onlyTheRiderMayReportAndOnlyDuringTheRental() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        String body = location("7.29", "80.63", null);

        for (User stranger : new User[] {saveUser(Role.USER), saveUser(Role.ADMIN)}) {
            mvc.perform(json(as(stranger, post("/api/bookings/" + rental.getBookingId() + "/location")), body))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(json(as(rider, post("/api/bookings/" + UUID.randomUUID() + "/location")), body))
                .andExpect(status().isNotFound());
        mvc.perform(json(post("/api/bookings/" + rental.getBookingId() + "/location"), body))
                .andExpect(status().isUnauthorized());

        for (Booking notUnderWay : new Booking[] {
                booking(rider, saveScooter(), BookingStatus.PENDING, now.minusHours(1)),
                // Paid and past its planned pickup time, but not handed over and started: not under way.
                booking(rider, saveScooter(), BookingStatus.READY_FOR_PICKUP, now.minusHours(1)),
                booking(rider, saveScooter(), BookingStatus.COMPLETED, now.minusHours(4)),
                booking(rider, saveScooter(), BookingStatus.CANCELLED, now.minusHours(1))}) {
            mvc.perform(json(as(rider, post("/api/bookings/" + notUnderWay.getBookingId() + "/location")), body))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Location can be reported only while the rental is under way"));
            assertThat(logOf(notUnderWay)).isEmpty();
        }
        assertThat(logOf(rental)).isEmpty();
    }

    @Test
    void impossibleRiderReportsAre400() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        String path = "/api/bookings/" + rental.getBookingId() + "/location";

        mvc.perform(json(as(rider, post(path)), location("-91", "80.63", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.latitude").exists());
        mvc.perform(json(as(rider, post(path)), location("7.29", "181", null)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.longitude").exists());
        mvc.perform(json(as(rider, post(path)), location("7.29", "80.63", "\"speedKmh\": -2")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.speedKmh").exists());
        mvc.perform(json(as(rider, post(path)), location("7.29", "80.63", "\"accuracyMeters\": -1")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.accuracyMeters").exists());
        assertThat(logOf(rental)).isEmpty();
    }

    // ── Admin live, trail and violations ──────────────────────────────────────

    @Test
    void liveMarksPositionsOlderThanTwoMinutesAsStale() throws Exception {
        Scooter fresh = saveScooter();
        fresh.setLatitude(7.29);
        fresh.setLongitude(80.63);
        fresh.setLastSeenAt(LocalDateTime.now().minusSeconds(30));
        scooters.saveAndFlush(fresh);
        Scooter old = saveScooter();
        old.setLatitude(7.30);
        old.setLongitude(80.64);
        old.setLastSeenAt(LocalDateTime.now().minusMinutes(3));
        scooters.saveAndFlush(old);
        Scooter never = saveScooter();

        String body = mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/gps/live")))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat((List<Boolean>) JsonPath.read(body, "$[?(@.scooterId == '" + fresh.getScooterId() + "')].stale"))
                .containsExactly(false);
        assertThat((List<Boolean>) JsonPath.read(body, "$[?(@.scooterId == '" + old.getScooterId() + "')].stale"))
                .containsExactly(true);
        assertThat((List<Double>) JsonPath.read(body, "$[?(@.scooterId == '" + old.getScooterId() + "')].latitude"))
                .containsExactly(7.30);
        assertThat((List<Boolean>) JsonPath.read(body, "$[?(@.scooterId == '" + never.getScooterId() + "')].stale"))
                .containsExactly(true);
        assertThat((List<Object>) JsonPath.read(body, "$[?(@.scooterId == '" + never.getScooterId() + "')].latitude"))
                .containsExactly((Object) null);
    }

    @Test
    void trailCoversTheRequestedHoursClampedToOneToOneHundredSixtyEight() throws Exception {
        User admin = saveUser(Role.ADMIN);
        Booking rental = booking(saveUser(Role.USER), saveScooter(), BookingStatus.ACTIVE, now.minusHours(5));
        storedPing(rental, "7.280000", "80.630000", now.minusMinutes(90));
        storedPing(rental, "7.290000", "80.630000", now.minusMinutes(30));
        String trail = "/api/admin/gps/scooters/" + rental.getScooter().getScooterId() + "/trail";

        mvc.perform(as(admin, get(trail).param("hours", "1"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.trail", hasSize(1)));
        mvc.perform(as(admin, get(trail).param("hours", "0"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.trail", hasSize(1)));
        mvc.perform(as(admin, get(trail).param("hours", "2"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.trail", hasSize(2)))
                .andExpect(jsonPath("$.trail[0].latitude").value(7.28))
                .andExpect(jsonPath("$.distanceKm").value(1.11));
        mvc.perform(as(admin, get(trail).param("hours", "100000"))).andExpect(status().isOk())
                .andExpect(jsonPath("$.trail", hasSize(2)));
        mvc.perform(as(admin, get("/api/admin/gps/scooters/" + UUID.randomUUID() + "/trail")))
                .andExpect(status().isNotFound());
    }

    private void violation(Scooter scooter, Booking booking, String kmh, LocalDateTime at) {
        SpeedViolation v = new SpeedViolation();
        v.setScooter(scooter);
        v.setBooking(booking);
        v.setSpeedKmh(new BigDecimal(kmh));
        v.setLatitude(new BigDecimal("7.290000"));
        v.setLongitude(new BigDecimal("80.630000"));
        v.setRecordedAt(at);
        v.setCreatedAt(at);
        violations.saveAndFlush(v);
    }

    /** Regression: the listing's entity graph named a Booking attribute that does not exist (customer). */
    @Test
    void speedViolationsAreListedNewestFirstWithTheRider() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        Scooter idle = saveScooter();
        violation(rental.getScooter(), rental, "72.50", now.minusMinutes(2));
        violation(idle, null, "65.00", now.minusMinutes(1));
        User admin = saveUser(Role.ADMIN);

        mvc.perform(as(admin, get("/api/admin/gps/speed-violations").param("limit", "10")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scooterCode").value(idle.getCode()))
                .andExpect(jsonPath("$[0].bookingReference").doesNotExist())
                .andExpect(jsonPath("$[1].speedKmh").value(72.50))
                .andExpect(jsonPath("$[1].bookingReference").value(rental.getReference()))
                .andExpect(jsonPath("$[1].riderName").value(rider.getFullName()));
        mvc.perform(as(admin, get("/api/admin/gps/speed-violations").param("limit", "0")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(1)));
        mvc.perform(as(admin, get("/api/admin/gps/speed-violations").param("limit", "500")))
                .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
    }

    /** A crossing above 60 km/h alerts admins in-app; no SMS is sent through the mock sender. */
    @Test
    void aSpeedViolationAlertsAdminsInAppOnly() throws Exception {
        User admin = saveUser(Role.ADMIN);
        Scooter scooter = saveScooter();
        booking(saveUser(Role.USER), scooter, BookingStatus.ACTIVE, now.minusHours(1));

        mvc.perform(fromDevice(DEVICE_KEY, devicePing(scooter, "7.29", "80.63", "70", now)))
                .andExpect(status().isAccepted());

        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(scooter.getScooterId())).hasSize(1);
        assertThat(notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(admin.getUserId(),
                Notification.Channel.IN_APP)).singleElement()
                .satisfies(n -> assertThat(n.getSubject()).isEqualTo("Speed violation: " + scooter.getCode()));
        assertThat(notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(admin.getUserId(),
                Notification.Channel.SMS)).isEmpty();
    }

    // ── Phone (rider) GPS speed: approximate, never alerted on one sample ─────

    private GpsPing storedPing(Booking b, String speedKmh, LocalDateTime at) {
        GpsPing p = storedPing(b, "7.290000", "80.630000", at);
        p.setSpeedKmh(new BigDecimal(speedKmh));
        return pings.saveAndFlush(p);
    }

    private long speedAlerts(User admin) {
        return notifications.findByUser_UserIdAndChannelOrderByCreatedAtDesc(admin.getUserId(),
                Notification.Channel.IN_APP).stream().filter(n -> n.getSubject().startsWith("Speed violation")).count();
    }

    @Test
    void oneSpikeFromThePhoneRaisesNoSpeedAlert() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        storedPing(rental, "12.00", LocalDateTime.now().minusMinutes(1));

        // The browser reported 126 km/h once: kept as approximate monitoring data, but no alert.
        mvc.perform(json(as(rider, post("/api/bookings/" + rental.getBookingId() + "/location")),
                        location("7.290100", "80.630000", "\"speedKmh\": 126")))
                .andExpect(status().isAccepted());

        assertThat(logOf(rental).getLast().getSpeedKmh()).isEqualByComparingTo("126.00");
        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(rental.getScooter().getScooterId())).isEmpty();
        assertThat(speedAlerts(admin)).isZero();
    }

    @Test
    void aSustainedRunOfCredibleOverLimitPhoneReadingsAlertsOnce() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        storedPing(rental, "80.00", LocalDateTime.now().minusMinutes(2));
        storedPing(rental, "85.00", LocalDateTime.now().minusMinutes(1));

        mvc.perform(json(as(rider, post("/api/bookings/" + rental.getBookingId() + "/location")),
                        location("7.290100", "80.630000", "\"speedKmh\": 90")))
                .andExpect(status().isAccepted());

        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(rental.getScooter().getScooterId()))
                .singleElement().satisfies(v -> assertThat(v.getSpeedKmh()).isEqualByComparingTo("90.00"));
        assertThat(speedAlerts(admin)).isEqualTo(1);
    }

    @Test
    void aRunAlreadyAlertedOrBrokenByAnImplausibleReadingRaisesNoFurtherAlert() throws Exception {
        User admin = saveUser(Role.ADMIN);
        User rider = saveUser(Role.USER);
        // Already over the limit for three readings before this one: the run was alerted when it began.
        Booking running = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        storedPing(running, "70.00", LocalDateTime.now().minusMinutes(3));
        storedPing(running, "80.00", LocalDateTime.now().minusMinutes(2));
        storedPing(running, "85.00", LocalDateTime.now().minusMinutes(1));
        mvc.perform(json(as(rider, post("/api/bookings/" + running.getBookingId() + "/location")),
                        location("7.290100", "80.630000", "\"speedKmh\": 90")))
                .andExpect(status().isAccepted());
        // An implausible reading (a GPS jump) in the run: not three credible readings.
        Booking jumpy = booking(saveUser(Role.USER), saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        storedPing(jumpy, "80.00", LocalDateTime.now().minusMinutes(2));
        storedPing(jumpy, "400.00", LocalDateTime.now().minusMinutes(1));
        mvc.perform(json(as(jumpy.getUser(), post("/api/bookings/" + jumpy.getBookingId() + "/location")),
                        location("7.290100", "80.630000", "\"speedKmh\": 90")))
                .andExpect(status().isAccepted());

        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(running.getScooter().getScooterId())).isEmpty();
        assertThat(violations.findByScooter_ScooterIdOrderByRecordedAtAsc(jumpy.getScooter().getScooterId())).isEmpty();
        assertThat(speedAlerts(admin)).isZero();
    }

    @Test
    void anImplausibleBrowserSpeedIsIgnoredInFavourOfTheSpeedFromThePositions() throws Exception {
        User rider = saveUser(Role.USER);
        Booking rental = booking(rider, saveScooter(), BookingStatus.ACTIVE, now.minusHours(1));
        // A previous report a minute ago, 0.01 degrees of latitude (about 1.11 km) away: about 66.7 km/h.
        storedPing(rental, "7.280000", "80.630000", LocalDateTime.now().minusSeconds(60));

        mvc.perform(json(as(rider, post("/api/bookings/" + rental.getBookingId() + "/location")),
                        location("7.290000", "80.630000", "\"speedKmh\": 400")))
                .andExpect(status().isAccepted());

        assertThat(logOf(rental)).hasSize(2).last()
                .satisfies(p -> assertThat(p.getSpeedKmh().doubleValue()).isCloseTo(66.7, within(1.5)));
    }

    @Test
    void adminGpsEndpointsAreForAdminsOnly() throws Exception {
        Scooter scooter = saveScooter();
        String[] paths = {"/api/admin/gps/live", "/api/admin/gps/scooters/" + scooter.getScooterId() + "/trail",
                "/api/admin/gps/speed-violations"};
        for (String path : paths) {
            mvc.perform(as(saveUser(Role.USER), get(path))).andExpect(status().isForbidden());
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
            mvc.perform(as(saveUser(Role.SUPER_ADMIN), get(path))).andExpect(status().isOk());
        }
    }

    // ── Public scooter API (privacy regression) ───────────────────────────────

    @Test
    void thePublicScooterApiShowsNoPosition() throws Exception {
        User rider = saveUser(Role.USER);
        Scooter rented = saveScooter();
        booking(rider, rented, BookingStatus.ACTIVE, now.minusHours(1));
        mvc.perform(fromDevice(DEVICE_KEY, devicePing(rented, "7.123456", "80.654321", "20", now)))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/scooters/" + rented.getScooterId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.latitude").doesNotExist())
                .andExpect(jsonPath("$.longitude").doesNotExist())
                .andExpect(jsonPath("$.lastSeenAt").doesNotExist())
                .andExpect(jsonPath("$.lastSpeedKmh").doesNotExist())
                .andExpect(jsonPath("$.code").value(rented.getCode()));
        String list = mvc.perform(get("/api/scooters")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(list).doesNotContain("7.123456", "80.654321");
        // Admins still see it on the fleet list.
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/scooters")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + rented.getScooterId() + "')].latitude")
                        .value(org.hamcrest.Matchers.contains(7.123456)));
    }
}
