package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.services.PasswordResetService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-041 without an email provider or frontend URL (the default test context, as a server with email not set up):
 * a request gets the usual answer, no link is created, and the log names the missing setting but holds no token.
 */
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class PasswordResetUnconfiguredTests extends PostgresIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordResetService passwordReset;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void forgetRequests() {
        passwordReset.clearRateLimits();
    }

    @Test
    void aRequestIsAnsweredAsUsualButNoLinkIsCreated(CapturedOutput output) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("noemail." + id + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("No Email Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("ne" + id));
        u.setRole(Role.USER);
        u.setIsActive(true);
        User user = users.saveAndFlush(u);

        mvc.perform(post("/api/auth/forgot-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\"}".formatted(user.getEmail())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(PasswordResetService.REQUEST_ACCEPTED));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM password_reset_tokens WHERE user_id = ?", Long.class,
                user.getUserId())).isZero();
        assertThat(output.getAll()).contains("is not configured; no email sent").doesNotContain("token=");
    }
}
