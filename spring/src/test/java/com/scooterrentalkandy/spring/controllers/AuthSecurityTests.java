package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import com.scooterrentalkandy.spring.config.AppProperties;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Auth failure paths, JWT validation and role boundaries over HTTP, through the real security filter chain, JWT
 * handling, service and embedded PostgreSQL. Users are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class AuthSecurityTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "secret-pass-1";
    private static final String ADMIN_ONLY = "/api/admin/customers";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtService jwt;

    @Autowired
    private AppProperties props;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("sec." + id + "@example.test");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setFullName("Security Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("S" + id);
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static String registration(String email, String nic) {
        return """
                {"email": "%s", "password": "%s", "fullName": "New Rider", "phone": "+94 70 000 0000",
                 "nicNumber": "%s", "idDocumentNumber": "", "drivingLicenseNo": "", "country": "",
                 "consent": true}
                """.formatted(email, PASSWORD, nic);
    }

    private static String login(String email, String password) {
        return """
                {"email": "%s", "password": "%s"}""".formatted(email, password);
    }

    /** Error bodies carry a fixed message only: no stack trace, SQL, constraint or exception class names. */
    private static void assertNoInternals(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("sql")
                .doesNotContain("users_email_key", "uq_users_nic", "constraint", "at com.", "at org.",
                        "passwordHash", "$2a$", "token");
    }

    // ── Registration conflicts ────────────────────────────────────────────────

    @Test
    void duplicateEmailIs409InAnyCaseWithoutInternalDetails() throws Exception {
        String id = unique();
        mvc.perform(json(post("/api/auth/register"), registration("dup." + id + "@example.test", TestNics.of("A" + id))))
                .andExpect(status().isCreated());

        for (String email : new String[] {"dup." + id + "@example.test", "DUP." + id + "@Example.TEST"}) {
            MvcResult result = mvc.perform(json(post("/api/auth/register"), registration(email, TestNics.of("B" + id))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.status").value(409))
                    .andExpect(jsonPath("$.message").value("An account with this email already exists"))
                    .andExpect(jsonPath("$.token").doesNotExist())
                    .andReturn();
            assertNoInternals(result);
        }
    }

    @Test
    void duplicateNicIs409WithoutInternalDetails() throws Exception {
        String id = unique();
        mvc.perform(json(post("/api/auth/register"), registration("first." + id + "@example.test", TestNics.of("N" + id))))
                .andExpect(status().isCreated());

        MvcResult result = mvc.perform(json(post("/api/auth/register"),
                        registration("second." + id + "@example.test", TestNics.of("N" + id))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("An account with this NIC number already exists"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();
        assertNoInternals(result);
        assertThat(users.existsByEmailIgnoreCase("second." + id + "@example.test")).isFalse();
    }

    @Test
    void registrationIgnoresProtectedFieldsInTheRequest() throws Exception {
        String id = unique();
        String email = "extra." + id + "@example.test";
        String extras = """
                "country": "", "id": "%s", "role": "SUPER_ADMIN", "passwordHash": "not-a-hash",
                 "active": false, "isActive": false, "createdAt": "2000-01-01T00:00:00\"""".formatted(UUID.randomUUID());
        String body = registration(email, TestNics.of("X" + id)).replace("\"country\": \"\"", extras);

        mvc.perform(json(post("/api/auth/register"), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.active").value(true));

        User saved = users.findByEmailIgnoreCase(email).orElseThrow();
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        assertThat(saved.getIsActive()).isTrue();
        assertThat(encoder.matches(PASSWORD, saved.getPasswordHash())).isTrue();
        assertThat(saved.getCreatedAt()).isAfter(LocalDateTime.of(2020, 1, 1, 0, 0));
    }

    // ── Login failures ────────────────────────────────────────────────────────

    @Test
    void unknownEmailAndWrongPasswordFailIdenticallyWith401() throws Exception {
        User rider = saveUser(Role.USER);

        MvcResult unknown = mvc.perform(json(post("/api/auth/login"), login("nobody." + unique() + "@example.test",
                        PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();
        MvcResult wrong = mvc.perform(json(post("/api/auth/login"), login(rider.getEmail(), "wrong-password")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid email or password"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();

        assertNoInternals(unknown);
        assertNoInternals(wrong);
        String withoutTimestamp = "\"timestamp\":\"[^\"]*\"";
        assertThat(unknown.getResponse().getContentAsString().replaceAll(withoutTimestamp, ""))
                .isEqualTo(wrong.getResponse().getContentAsString().replaceAll(withoutTimestamp, ""));
    }

    @Test
    void deactivatedAccountCannotLogInEvenWithTheRightPassword() throws Exception {
        User rider = saveUser(Role.USER);
        rider.setIsActive(false);
        users.saveAndFlush(rider);

        MvcResult result = mvc.perform(json(post("/api/auth/login"), login(rider.getEmail(), PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("This account has been deactivated"))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andReturn();
        assertNoInternals(result);
    }

    // ── JWT validation ────────────────────────────────────────────────────────

    @Test
    void missingOrMalformedTokensAre401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Bearer ")).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/me"), "not-a-jwt")).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/me"), "aaa.bbb.ccc")).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/me"), UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
        // A valid token under another scheme is not accepted.
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, "Basic " + jwt.issue(saveUser(Role.USER))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tamperedTokenIs401() throws Exception {
        User rider = saveUser(Role.USER);
        String[] parts = jwt.issue(rider).split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String elevated = payload.replace("\"role\":\"USER\"", "\"role\":\"ADMIN\"");
        assertThat(elevated).isNotEqualTo(payload);
        String tampered = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding().encodeToString(elevated.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        mvc.perform(withToken(get("/api/me"), tampered)).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get(ADMIN_ONLY), tampered)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherSecretIs401() throws Exception {
        User rider = saveUser(Role.USER);
        SecretKey otherKey = Jwts.SIG.HS256.key().build();
        String forged = Jwts.builder()
                .subject(rider.getUserId().toString())
                .claim("email", rider.getEmail())
                .claim("role", "ADMIN")
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(otherKey)
                .compact();

        mvc.perform(withToken(get("/api/me"), forged)).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get(ADMIN_ONLY), forged)).andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIs401() throws Exception {
        User rider = saveUser(Role.USER);
        // Signed with the application's own key, so only the expiry is wrong.
        SecretKey appKey = Keys.hmacShaKeyFor(props.jwt().secret().getBytes(StandardCharsets.UTF_8));
        Instant issued = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = Jwts.builder()
                .subject(rider.getUserId().toString())
                .claim("email", rider.getEmail())
                .claim("role", rider.getRole().name())
                .issuedAt(Date.from(issued))
                .expiration(Date.from(issued.plus(1, ChronoUnit.HOURS)))
                .signWith(appKey)
                .compact();
        String fresh = Jwts.builder()
                .subject(rider.getUserId().toString())
                .claim("email", rider.getEmail())
                .claim("role", rider.getRole().name())
                .issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(appKey)
                .compact();

        mvc.perform(withToken(get("/api/me"), fresh)).andExpect(status().isOk());
        mvc.perform(withToken(get("/api/me"), expired)).andExpect(status().isUnauthorized());
    }

    // ── Role boundaries ───────────────────────────────────────────────────────

    @Test
    void userCanUseMeButNotAdminRoutes() throws Exception {
        String token = jwt.issue(saveUser(Role.USER));

        mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isOk());
        mvc.perform(json(withToken(put("/api/me"), token), """
                        {"fullName": "Still A User", "phone": "+94 70 000 0000"}"""))
                .andExpect(status().isOk());
        mvc.perform(withToken(get(ADMIN_ONLY), token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You do not have access to this resource"));
    }

    @Test
    void adminAndSuperAdminCanUseMeAndAdminRoutes() throws Exception {
        for (Role role : new Role[] {Role.ADMIN, Role.SUPER_ADMIN}) {
            String token = jwt.issue(saveUser(role));

            mvc.perform(withToken(get("/api/me"), token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.role").value(role.name()));
            mvc.perform(withToken(get(ADMIN_ONLY), token)).andExpect(status().isOk());
        }
    }

    // ── The account's current state decides, not the token's claims ──────────

    @Test
    void tokenStopsWorkingOnceTheUserIsDeactivated() throws Exception {
        User rider = saveUser(Role.USER);
        String token = jwt.issue(rider);
        mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isOk());

        rider.setIsActive(false);
        users.saveAndFlush(rider);

        mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get("/api/bookings"), token)).andExpect(status().isUnauthorized());
    }

    @Test
    void tokenForADeletedOrUnknownUserIs401() throws Exception {
        User rider = saveUser(Role.USER);
        String token = jwt.issue(rider);
        mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isOk());

        users.delete(rider);
        users.flush();
        mvc.perform(withToken(get("/api/me"), token)).andExpect(status().isUnauthorized());

        User ghost = new User();
        ghost.setUserId(UUID.randomUUID());
        ghost.setEmail("ghost@example.test");
        ghost.setRole(Role.ADMIN);
        String ghostToken = jwt.issue(ghost);
        mvc.perform(withToken(get("/api/me"), ghostToken)).andExpect(status().isUnauthorized());
        mvc.perform(withToken(get(ADMIN_ONLY), ghostToken)).andExpect(status().isUnauthorized());
    }

    @Test
    void demotedAdminLosesAdminAccessWithTheOldToken() throws Exception {
        User admin = saveUser(Role.ADMIN);
        String token = jwt.issue(admin);
        mvc.perform(withToken(get(ADMIN_ONLY), token)).andExpect(status().isOk());

        admin.setRole(Role.USER);
        users.saveAndFlush(admin);

        mvc.perform(withToken(get(ADMIN_ONLY), token)).andExpect(status().isForbidden());
        mvc.perform(withToken(get("/api/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getUserId().toString()))
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    void currentEmailIsUsedWithAnOlderToken() throws Exception {
        User rider = saveUser(Role.USER);
        String token = jwt.issue(rider);
        String newEmail = "changed." + unique() + "@example.test";

        rider.setEmail(newEmail);
        users.saveAndFlush(rider);

        mvc.perform(withToken(get("/api/me"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(newEmail));
    }
}
