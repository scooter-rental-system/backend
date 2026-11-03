package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import com.scooterrentalkandy.spring.repository.BookingRepository;
import com.scooterrentalkandy.spring.repository.ScooterRepository;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional photo evidence on repair reports (BE-043): up to three JPEG/PNG/WebP images of at most 5 MB, checked by
 * their content; served only to the booking's customer and admins. Each test rolls back, which also removes the
 * stored files.
 */
@AutoConfigureMockMvc
@Transactional
class RepairPhotoTests extends PostgresIntegrationTest {

    static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    static final byte[] WEBP = Base64.getDecoder().decode("UklGRhoAAABXRUJQVlA4TA0AAAAvAAAAEAcQERGIiP4HAA==");
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00,
            0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, (byte) 0xFF, (byte) 0xD9};

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private ScooterRepository scooters;

    @Autowired
    private BookingRepository bookings;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtService jwt;

    @Autowired
    private EntityManager em;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("photo." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Photo Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("photo" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private Booking inProgress(User customer) {
        String id = unique();
        Scooter s = new Scooter();
        s.setCode("PH-" + id);
        s.setModel("Photo Dio");
        s.setPlateNumber("PHP-" + id);
        s.setDailyRate(new BigDecimal("2500.00"));
        s.setHourlyRate(new BigDecimal("250.00"));
        s.setPerKmRate(new BigDecimal("15.00"));
        s.setStatus(ScooterStatus.RENTED);
        scooters.saveAndFlush(s);
        Booking b = new Booking();
        b.setReference("BK-PH" + id);
        b.setUser(customer);
        b.setScooter(s);
        b.setStatus(BookingStatus.ACTIVE);
        b.setStartTime(LocalDateTime.now().minusHours(2));
        b.setActualStartTime(b.getStartTime());
        b.setContractSigned(true);
        b.setPickupLocation("Kandy office");
        return bookings.saveAndFlush(b);
    }

    private MockHttpServletRequestBuilder as(User user, MockHttpServletRequestBuilder request) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
    }

    private static MockMultipartFile reportPart(String json) {
        return new MockMultipartFile("report", "", MediaType.APPLICATION_JSON_VALUE, json.getBytes(StandardCharsets.UTF_8));
    }

    private static MockMultipartFile photo(String name, String type, byte[] bytes) {
        return new MockMultipartFile("photos", name, type, bytes);
    }

    private ResultActions submit(User user, Booking b, MockMultipartFile... photos) throws Exception {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/bookings/" + b.getBookingId() + "/repairs");
        request.file(reportPart("{\"description\": \"Front brake cable broke\", \"selfArranged\": true, \"claimedAmount\": 1800}"));
        for (MockMultipartFile p : photos) {
            request.file(p);
        }
        request.header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user));
        return mvc.perform(request);
    }

    private long reportsOf(Booking b) {
        em.flush();
        return jdbc.queryForObject("SELECT count(*) FROM repair_reports WHERE booking_id = ?", Long.class, b.getBookingId());
    }

    @Test
    void aReportWithoutPhotosStillWorksAsJsonAndAsMultipart() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);

        mvc.perform(as(customer, post("/api/bookings/" + b.getBookingId() + "/repairs"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\": \"Flat tyre\", \"selfArranged\": false}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(0));
        submit(customer, b).andExpect(status().isCreated())
                .andExpect(jsonPath("$.claimedAmount").value(1800))
                .andExpect(jsonPath("$.approvedAmount").doesNotExist())
                .andExpect(jsonPath("$.photos.length()").value(0));
    }

    @Test
    void upToThreeValidImagesAreAttachedWithoutExposingStorageDetails() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);

        String body = submit(customer, b, photo("tyre.png", "image/png", PNG), photo("receipt.JPG", "image/jpeg", JPEG),
                        photo("bike.webp", "image/webp", WEBP))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.photos.length()").value(3))
                .andExpect(jsonPath("$.photos[0].contentType").value("image/png"))
                .andExpect(jsonPath("$.photos[1].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.photos[2].contentType").value("image/webp"))
                .andExpect(jsonPath("$.photos[0].sizeBytes").value(PNG.length))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("tyre.png", "receipt", "storage", "repair-photos", "data/", "\\\\", ".png\"");
        em.flush();
        assertThat(jdbc.queryForList("SELECT storage_key FROM repair_photos rp JOIN repair_reports rr "
                + "ON rr.repair_report_id = rp.repair_report_id WHERE rr.booking_id = ?", String.class, b.getBookingId()))
                .hasSize(3).allMatch(k -> k.matches("[0-9a-f-]{36}\\.(png|jpg|webp)"));
    }

    @Test
    void filesThatAreNotJpegPngOrWebpAreRefusedAndNothingIsSaved() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        byte[] html = "<!DOCTYPE html><html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        byte[] exe = {'M', 'Z', (byte) 0x90, 0x00, 0x03, 0x00, 0x00, 0x00, 0x04, 0x00, 0x00, 0x00};
        byte[] gif = "GIF89a\u0001\u0000\u0001\u0000".getBytes(StandardCharsets.ISO_8859_1);

        for (MockMultipartFile bad : new MockMultipartFile[] {
                photo("evidence.svg", "image/svg+xml", svg),
                photo("evidence.png", "image/png", svg),
                photo("evidence.png", "image/png", html),
                photo("evidence.png", "image/png", exe),
                photo("evidence.exe", "application/octet-stream", exe),
                photo("evidence.gif", "image/gif", gif),
                photo("evidence.jpg", "image/jpeg", PNG),
                photo("evidence.png", "image/jpeg", PNG),
                photo("evidence.png.html", "image/png", PNG)}) {
            submit(customer, b, photo("ok.png", "image/png", PNG), bad)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Photos must be JPEG, PNG or WebP images."));
        }
        assertThat(reportsOf(b)).isZero();
    }

    @Test
    void emptyCorruptOversizedAndTooManyPhotosAreRefused() throws Exception {
        User customer = saveUser(Role.USER);
        Booking b = inProgress(customer);
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG, 0, big, 0, PNG.length);
        byte[] truncated = java.util.Arrays.copyOf(PNG, 10);
        byte[] jpegWithoutEnd = java.util.Arrays.copyOf(JPEG, JPEG.length - 2);

        submit(customer, b, photo("empty.png", "image/png", new byte[0])).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A photo is empty. Choose the image again."));
        submit(customer, b, photo("big.png", "image/png", big)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Each photo must be 5 MB or smaller."));
        submit(customer, b, photo("cut.png", "image/png", truncated)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("A photo could not be read as an image. Choose another file."));
        submit(customer, b, photo("cut.jpg", "image/jpeg", jpegWithoutEnd)).andExpect(status().isBadRequest());
        submit(customer, b, photo("1.png", "image/png", PNG), photo("2.png", "image/png", PNG),
                photo("3.png", "image/png", PNG), photo("4.png", "image/png", PNG))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Attach at most 3 photos."));
        assertThat(reportsOf(b)).isZero();
    }

    @Test
    void theOwnerAndAdminsCanViewAPhotoButNobodyElse() throws Exception {
        User owner = saveUser(Role.USER);
        Booking b = inProgress(owner);
        String body = submit(owner, b, photo("tyre.png", "image/png", PNG)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String reportId = JsonPath.read(body, "$.id");
        String photoId = JsonPath.read(body, "$.photos[0].id");
        String url = "/api/repairs/" + reportId + "/photos/" + photoId;

        mvc.perform(as(owner, get(url)))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(as(saveUser(Role.ADMIN), get(url))).andExpect(status().isOk()).andExpect(content().bytes(PNG));
        mvc.perform(as(saveUser(Role.SUPER_ADMIN), get(url))).andExpect(status().isOk());

        mvc.perform(as(saveUser(Role.USER), get(url))).andExpect(status().isNotFound());
        mvc.perform(get(url)).andExpect(status().isUnauthorized());
        mvc.perform(as(owner, get("/api/repairs/" + reportId + "/photos/" + UUID.randomUUID())))
                .andExpect(status().isNotFound());
        mvc.perform(as(owner, get("/api/repairs/" + reportId + "/photos/..%2F..%2Fapplication.properties")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPhotoIsOnlyServedThroughItsOwnReport() throws Exception {
        User owner = saveUser(Role.USER);
        Booking b = inProgress(owner);
        String first = submit(owner, b, photo("a.png", "image/png", PNG)).andReturn().getResponse().getContentAsString();
        String second = submit(owner, b).andReturn().getResponse().getContentAsString();

        mvc.perform(as(owner, get("/api/repairs/" + JsonPath.read(second, "$.id") + "/photos/"
                        + JsonPath.read(first, "$.photos[0].id"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void reportsAndTheirPhotosAreListedForTheOwnerAndAdmins() throws Exception {
        User owner = saveUser(Role.USER);
        Booking b = inProgress(owner);
        submit(owner, b, photo("a.png", "image/png", PNG)).andExpect(status().isCreated());

        mvc.perform(as(owner, get("/api/bookings/" + b.getBookingId() + "/repairs")))
                .andExpect(jsonPath("$[0].photos.length()").value(1));
        mvc.perform(as(saveUser(Role.ADMIN), get("/api/admin/repairs")))
                .andExpect(jsonPath("$[?(@.bookingId == '" + b.getBookingId() + "')].photos.length()").value(1));
    }

    @Test
    void anotherCustomerCannotReportOnTheRentalWithPhotos() throws Exception {
        Booking b = inProgress(saveUser(Role.USER));

        submit(saveUser(Role.USER), b, photo("a.png", "image/png", PNG)).andExpect(status().isNotFound());
        assertThat(reportsOf(b)).isZero();
    }
}
