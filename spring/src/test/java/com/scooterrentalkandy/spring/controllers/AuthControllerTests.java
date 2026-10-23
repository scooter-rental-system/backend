package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.jayway.jsonpath.JsonPath;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The auth endpoints over HTTP, through the real security filter chain, JWT handling, validation, service and
 * embedded PostgreSQL. Users are synthetic and each test rolls back.
 */
@AutoConfigureMockMvc
@Transactional
class AuthControllerTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtService jwt;

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private User saveUser(Role role) {
        String id = unique();
        User u = new User();
        u.setEmail("http." + id + "@example.test");
        u.setPasswordHash(encoder.encode("secret-pass-1"));
        u.setFullName("Http Rider " + id);
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("H" + id);
        u.setCountry("Sri Lanka");
        u.setRole(role);
        return users.saveAndFlush(u);
    }

    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String registration(String email, String password, String nic) {
        String nicField = nic == null ? "" : """
                "nicNumber": "%s",""".formatted(nic);
        return """
                {"email": "%s", "password": "%s", "fullName": "New Rider", "phone": "+94 70 000 0000", %s
                 "idDocumentNumber": "", "drivingLicenseNo": "", "country": "Sri Lanka"}
                """.formatted(email, password, nicField);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void registerCreatesAUserAndReturns201WithTokenAndUser() throws Exception {
        String id = unique();
        mvc.perform(json(post("/api/auth/register"), registration("New." + id + "@Example.Test", "secret-pass-1",
                        "R" + id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.id").isString())
                .andExpect(jsonPath("$.user.email").value("new." + id + "@example.test"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.active").value(true))
                .andExpect(jsonPath("$.user.idDocumentNumber").doesNotExist())
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(jsonPath("$.user.nicNumber").doesNotExist());

        assertThat(users.findByEmailIgnoreCase("new." + id + "@example.test")).get()
                .satisfies(u -> assertThat(u.getNicNumber()).isEqualTo("R" + id));
    }

    @Test
    void registerIgnoresAClientSuppliedRole() throws Exception {
        String id = unique();
        String body = registration("role." + id + "@example.test", "secret-pass-1", "R" + id)
                .replace("\"country\"", "\"role\": \"ADMIN\", \"country\"");

        mvc.perform(json(post("/api/auth/register"), body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void registerWithoutANicIs400() throws Exception {
        mvc.perform(json(post("/api/auth/register"), registration("no.nic." + unique() + "@example.test",
                        "secret-pass-1", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.nicNumber").exists());
    }

    @Test
    void registerWithAnInvalidEmailIs400() throws Exception {
        mvc.perform(json(post("/api/auth/register"), registration("not-an-email", "secret-pass-1", "R" + unique())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists());
    }

    @Test
    void registerWithAShortPasswordIs400() throws Exception {
        mvc.perform(json(post("/api/auth/register"), registration("short." + unique() + "@example.test", "short7!",
                        "R" + unique())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void loginReturns200WithTokenAndUser() throws Exception {
        User rider = saveUser(Role.USER);

        mvc.perform(json(post("/api/auth/login"), """
                        {"email": "%s", "password": "secret-pass-1"}""".formatted(rider.getEmail().toUpperCase())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isString())
                .andExpect(jsonPath("$.user.id").value(rider.getUserId().toString()))
                .andExpect(jsonPath("$.user.email").value(rider.getEmail()))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.user.nicNumber").doesNotExist());
    }

    @Test
    void loginWithAnInvalidRequestIs400() throws Exception {
        mvc.perform(json(post("/api/auth/login"), """
                        {"email": "not-an-email", "password": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.email").exists())
                .andExpect(jsonPath("$.fieldErrors.password").exists());
    }

    @Test
    void tokenFromLoginOpensTheCurrentUser() throws Exception {
        User rider = saveUser(Role.USER);
        String response = mvc.perform(json(post("/api/auth/login"), """
                        {"email": "%s", "password": "secret-pass-1"}""".formatted(rider.getEmail())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(response, "$.token");

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rider.getUserId().toString()));
    }

    // ── /api/me ───────────────────────────────────────────────────────────────

    @Test
    void meWithoutATokenIs401() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(json(put("/api/me"), """
                        {"fullName": "Nobody", "phone": "+94 70 000 0000"}"""))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void meReturnsTheUserNamedByTheToken() throws Exception {
        User rider = saveUser(Role.USER);
        User other = saveUser(Role.USER);

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt.issue(rider)))
                        .param("userId", other.getUserId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(rider.getUserId().toString()))
                .andExpect(jsonPath("$.email").value(rider.getEmail()))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.nicNumber").doesNotExist());
    }

    @Test
    void meIsAvailableToAdmins() throws Exception {
        User admin = saveUser(Role.ADMIN);

        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt.issue(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(admin.getUserId().toString()))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    void updateMeChangesOnlyTheProfileFields() throws Exception {
        User rider = saveUser(Role.USER);
        String email = rider.getEmail();
        String nic = rider.getNicNumber();
        String hash = rider.getPasswordHash();

        mvc.perform(json(put("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt.issue(rider))), """
                        {"fullName": "Renamed Rider", "phone": "+94 77 222 2222", "idDocumentNumber": "",
                         "drivingLicenseNo": "B7654321", "country": "India",
                         "email": "taken.over@example.test", "nicNumber": "CHANGED", "role": "ADMIN",
                         "password": "new-password-1", "passwordHash": "x", "active": false, "isActive": false}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullName").value("Renamed Rider"))
                .andExpect(jsonPath("$.phone").value("+94 77 222 2222"))
                .andExpect(jsonPath("$.drivingLicenseNo").value("B7654321"))
                .andExpect(jsonPath("$.country").value("India"))
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.active").value(true));

        assertThat(users.findById(rider.getUserId())).get().satisfies(u -> {
            assertThat(u.getEmail()).isEqualTo(email);
            assertThat(u.getNicNumber()).isEqualTo(nic);
            assertThat(u.getRole()).isEqualTo(Role.USER);
            assertThat(u.getPasswordHash()).isEqualTo(hash);
            assertThat(u.getIsActive()).isTrue();
            assertThat(u.getIdDocumentNumber()).isNull();
        });
    }

    @Test
    void updateMeValidatesTheRequest() throws Exception {
        User rider = saveUser(Role.USER);

        mvc.perform(json(put("/api/me").header(HttpHeaders.AUTHORIZATION, bearer(jwt.issue(rider))), """
                        {"fullName": " ", "phone": ""}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.fullName").exists())
                .andExpect(jsonPath("$.fieldErrors.phone").exists());
    }
}
