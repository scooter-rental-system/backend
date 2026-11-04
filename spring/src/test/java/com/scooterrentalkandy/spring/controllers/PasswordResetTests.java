package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.services.PasswordResetService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.RecordingEmailSender;
import com.scooterrentalkandy.spring.support.RecordingEmailSender.Sent;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * BE-041 forgot password over HTTP, with an email provider (the test-only {@link RecordingEmailSender}) and a
 * frontend URL configured. The reset email is sent only after the request commits, so these requests really commit;
 * the users they create are deleted after each test (their tokens go with them).
 */
@AutoConfigureMockMvc
@Import(RecordingEmailSender.class)
@TestPropertySource(properties = "app.frontend-url=https://rent.example.test/")
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetTests extends PostgresIntegrationTest {

    private static final String OLD = "old-password-1";
    private static final String NEW = "brand-new-pass-2";
    private static final Pattern LINK = Pattern.compile(
            "https://rent\\.example\\.test/reset-password\\?token=([A-Za-z0-9_-]{43})");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RecordingEmailSender email;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private PasswordResetService passwordReset;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<UUID> createdUsers = new ArrayList<>();

    @BeforeEach
    void setUp() {
        email.reset();
        passwordReset.clearRateLimits();
    }

    @AfterEach
    void deleteFixtures() {
        for (UUID id : createdUsers) {
            jdbc.update("DELETE FROM notifications WHERE user_id = ?", id);
            jdbc.update("DELETE FROM users WHERE user_id = ?", id);
        }
        createdUsers.clear();
        passwordReset.clearRateLimits();
    }

    private User saveUser(Role role, boolean active) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("reset." + id + "@example.test");
        u.setPasswordHash(encoder.encode(OLD));
        u.setFullName("Reset Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("rs" + id));
        u.setRole(role);
        u.setIsActive(active);
        User saved = users.saveAndFlush(u);
        createdUsers.add(saved.getUserId());
        return saved;
    }

    private User customer() {
        return saveUser(Role.USER, true);
    }

    /** A documentation-range address per test, so the per-address limit of one test never affects another. */
    private static String address() {
        return "203.0.113." + ThreadLocalRandom.current().nextInt(1, 255);
    }

    private ResultActions forgot(String emailAddress) throws Exception {
        return forgot(emailAddress, address());
    }

    private ResultActions forgot(String emailAddress, String from) throws Exception {
        return mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\"}".formatted(emailAddress))
                .with(request -> {
                    request.setRemoteAddr(from);
                    return request;
                }));
    }

    private ResultActions reset(String token, String newPassword) throws Exception {
        return mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted(token, newPassword)));
    }

    private ResultActions login(String emailAddress, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(emailAddress, password)));
    }

    /** Requests a reset and returns the raw token from the emailed link. */
    private String requestToken(User user) throws Exception {
        int before = resetEmails(user).size();
        forgot(user.getEmail()).andExpect(status().isOk());
        List<Sent> sent = resetEmails(user);
        assertThat(sent).hasSize(before + 1);
        Matcher m = LINK.matcher(sent.getLast().body());
        assertThat(m.find()).as("the email carries the reset link").isTrue();
        return m.group(1);
    }

    private List<Sent> resetEmails(User user) {
        return email.sentTo(user.getEmail()).stream()
                .filter(s -> s.subject().equals("Scooter Rent Kandy password reset")).toList();
    }

    private List<String> storedHashes(User user) {
        return jdbc.queryForList("SELECT token_hash FROM password_reset_tokens WHERE user_id = ?", String.class,
                user.getUserId());
    }

    @Test
    void anExistingAccountIsEmailedAOneTimeLinkWithTheGenericAnswer() throws Exception {
        User user = customer();

        forgot(user.getEmail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.REQUEST_ACCEPTED));

        assertThat(resetEmails(user)).singleElement().satisfies(sent -> {
            assertThat(sent.body()).containsPattern(LINK).contains("30 minutes")
                    .contains("If you did not request a reset, you can ignore this email");
            assertThat(sent.body()).doesNotContain(user.getFullName()).doesNotContain(user.getPhone());
        });
    }

    @Test
    void anUnknownEmailGetsTheSameAnswerAndNothingIsSentOrStored() throws Exception {
        long tokensBefore = jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Long.class);

        String unknown = forgot("nobody." + UUID.randomUUID() + "@example.test").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        User user = customer();
        String known = forgot(user.getEmail()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(unknown).as("no account enumeration").isEqualTo(known);
        assertThat(email.sent()).allMatch(s -> s.to().equals(user.getEmail()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens", Long.class))
                .isEqualTo(tokensBefore + 1);
    }

    @Test
    void anInactiveAccountGetsTheSameAnswerButNoEmail() throws Exception {
        User blocked = saveUser(Role.USER, false);

        forgot(blocked.getEmail()).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.REQUEST_ACCEPTED));

        assertThat(email.sentTo(blocked.getEmail())).isEmpty();
        assertThat(storedHashes(blocked)).isEmpty();
    }

    @Test
    void theEmailIsNormalisedLikeLogin() throws Exception {
        User user = customer();

        forgot(user.getEmail().toUpperCase()).andExpect(status().isOk());

        assertThat(resetEmails(user)).hasSize(1);
    }

    @Test
    void onlyTheHashOfTheTokenIsStored() throws Exception {
        User user = customer();

        String token = requestToken(user);

        assertThat(storedHashes(user)).singleElement().satisfies(hash -> {
            assertThat(hash).isNotEqualTo(token).doesNotContain(token).matches("[0-9a-f]{64}");
        });
        Long rawAnywhere = jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE token_hash = ?",
                Long.class, token);
        assertThat(rawAnywhere).isZero();
    }

    @Test
    void theEmailLinkIsNeitherStoredInNotificationsNorLogged(CapturedOutput output) throws Exception {
        User user = customer();

        String token = requestToken(user);

        List<String> stored = jdbc.queryForList("SELECT subject || ' ' || message FROM notifications WHERE user_id = ?",
                String.class, user.getUserId());
        assertThat(stored).singleElement().asString().contains("password reset").doesNotContain(token);
        assertThat(output.getAll()).doesNotContain(token);
    }

    @Test
    void aValidLinkSetsTheNewPasswordWithBcryptAndLoginUsesIt() throws Exception {
        User user = customer();
        String token = requestToken(user);

        reset(token, NEW).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.RESET_DONE))
                .andExpect(jsonPath("$.token").doesNotExist());

        String hash = users.findById(user.getUserId()).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$12$");
        login(user.getEmail(), NEW).andExpect(status().isOk());
        login(user.getEmail(), OLD).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCanResetTooAndIsToldInTheApp() throws Exception {
        User admin = saveUser(Role.ADMIN, true);
        String token = requestToken(admin);

        reset(token, NEW).andExpect(status().isOk());

        login(admin.getEmail(), NEW).andExpect(status().isOk());
        List<String> inApp = jdbc.queryForList(
                "SELECT subject FROM notifications WHERE user_id = ? AND channel = 'IN_APP'", String.class,
                admin.getUserId());
        assertThat(inApp).containsExactly("Your password was reset");
        assertThat(email.sentTo(admin.getEmail())).extracting(Sent::subject).contains("Your password was reset");
    }

    @Test
    void aLinkWorksOnlyOnce() throws Exception {
        User user = customer();
        String token = requestToken(user);
        reset(token, NEW).andExpect(status().isOk());

        reset(token, "another-pass-3").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("already been used")));

        login(user.getEmail(), NEW).andExpect(status().isOk());
    }

    @Test
    void aNewerRequestReplacesTheEarlierLink() throws Exception {
        User user = customer();
        String first = requestToken(user);
        String second = requestToken(user);

        reset(first, NEW).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("replaced by a newer one")));
        reset(second, NEW).andExpect(status().isOk());
    }

    @Test
    void anExpiredLinkIsRejected() throws Exception {
        User user = customer();
        String token = requestToken(user);
        jdbc.update("UPDATE password_reset_tokens SET created_at = created_at - interval '1 hour', "
                + "expires_at = expires_at - interval '1 hour' WHERE user_id = ?", user.getUserId());

        reset(token, NEW).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("expired")));

        login(user.getEmail(), OLD).andExpect(status().isOk());
    }

    @Test
    void theLinkExpiresAfterThirtyMinutes() throws Exception {
        User user = customer();
        requestToken(user);

        Long minutes = jdbc.queryForObject("SELECT (extract(epoch FROM expires_at - created_at) / 60)::bigint "
                + "FROM password_reset_tokens WHERE user_id = ?", Long.class, user.getUserId());
        assertThat(minutes).isEqualTo(30);
    }

    @Test
    void anUnknownTokenIsRejected() throws Exception {
        reset("x".repeat(43), NEW).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("not valid")));
    }

    @Test
    void theNewPasswordFollowsThePolicyAndABadOneLeavesTheLinkUsable() throws Exception {
        User user = customer();
        String token = requestToken(user);

        reset(token, "short").andExpect(status().isBadRequest());
        reset(token, "x".repeat(73)).andExpect(status().isBadRequest());
        reset("", NEW).andExpect(status().isBadRequest());

        reset(token, NEW).andExpect(status().isOk());
    }

    @Test
    void anInvalidEmailIsRejectedByValidation() throws Exception {
        forgot("not-an-email").andExpect(status().isBadRequest());
    }

    @Test
    void tooManyRequestsFromOneAddressAreLimitedWhateverTheEmail() throws Exception {
        String from = address();
        for (int i = 0; i < 5; i++) {
            forgot("someone" + i + "." + UUID.randomUUID() + "@example.test", from).andExpect(status().isOk());
        }

        forgot("someone.else@example.test", from).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.message").value(containsString("Too many password reset requests")));
        forgot("someone.else@example.test", address()).andExpect(status().isOk());
    }

    @Test
    void oneAccountGetsAtMostThreeEmailsPerWindowWithTheSameAnswer() throws Exception {
        User user = customer();
        for (int i = 0; i < 4; i++) {
            forgot(user.getEmail()).andExpect(status().isOk())
                    .andExpect(jsonPath("$.message").value(PasswordResetService.REQUEST_ACCEPTED));
        }

        assertThat(resetEmails(user)).hasSize(3);
    }

    @Test
    void bothEndpointsWorkWithoutSigningIn() throws Exception {
        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"public@example.test\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/reset-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"%s\", \"newPassword\": \"%s\"}".formatted("y".repeat(43), NEW)))
                .andExpect(status().isBadRequest());
    }
}
