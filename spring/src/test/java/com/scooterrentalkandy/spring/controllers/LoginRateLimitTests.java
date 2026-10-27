package com.scooterrentalkandy.spring.controllers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.LoginRateLimiter;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-035 (SDS 6.1) over HTTP: 5 failed logins per minute per client address, then 429 with Retry-After. Each test
 * uses its own documentation-range address, so tests never affect each other or other test classes.
 */
@AutoConfigureMockMvc
@Transactional
class LoginRateLimitTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "secret-pass-1";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private LoginRateLimiter limiter;

    @AfterEach
    void forgetFailures() {
        limiter.clear();
    }

    private static String address() {
        return "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
    }

    private User saveUser() {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        User u = new User();
        u.setEmail("limit." + id + "@example.test");
        u.setPasswordHash(encoder.encode(PASSWORD));
        u.setFullName("Limit Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("limit" + id));
        u.setRole(Role.USER);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private ResultActions login(String from, String email, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(email, password))
                .with(request -> {
                    request.setRemoteAddr(from);
                    return request;
                }));
    }

    @Test
    void theSixthFailedLoginWithinAMinuteIs429WithRetryAfter() throws Exception {
        User user = saveUser();
        String from = address();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            login(from, user.getEmail(), "wrong-pass-1").andExpect(status().isUnauthorized());
        }
        login(from, user.getEmail(), "wrong-pass-1")
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "60"))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value(
                        "Too many failed sign-in attempts. Please try again in 60 seconds."));
    }

    @Test
    void aBlockedAddressIsRefusedEvenWithTheRightPassword() throws Exception {
        User user = saveUser();
        String from = address();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            login(from, user.getEmail(), "wrong-pass-1");
        }
        login(from, user.getEmail(), PASSWORD).andExpect(status().isTooManyRequests());
    }

    @Test
    void unknownEmailsCountAsFailures() throws Exception {
        String from = address();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            login(from, "nobody." + i + "@example.test", "wrong-pass-1").andExpect(status().isUnauthorized());
        }
        login(from, "nobody.else@example.test", "wrong-pass-1").andExpect(status().isTooManyRequests());
    }

    @Test
    void anotherAddressIsNotAffected() throws Exception {
        User user = saveUser();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES; i++) {
            login("198.51.100.250", user.getEmail(), "wrong-pass-1");
        }
        login("198.51.100.251", user.getEmail(), PASSWORD).andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void successfulLoginsAreNeverCounted() throws Exception {
        User user = saveUser();
        String from = address();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES + 3; i++) {
            login(from, user.getEmail(), PASSWORD).andExpect(status().isOk());
        }
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES - 1; i++) {
            login(from, user.getEmail(), "wrong-pass-1").andExpect(status().isUnauthorized());
        }
        login(from, user.getEmail(), PASSWORD).andExpect(status().isOk());
    }

    @Test
    void malformedRequestsAreNotCountedAsFailedSignIns() throws Exception {
        String from = address();
        for (int i = 0; i < LoginRateLimiter.MAX_FAILURES + 2; i++) {
            login(from, "not-an-email", "x").andExpect(status().isBadRequest());
        }
        User user = saveUser();
        login(from, user.getEmail(), PASSWORD).andExpect(status().isOk());
    }
}
