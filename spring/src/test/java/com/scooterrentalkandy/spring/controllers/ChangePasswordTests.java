package com.scooterrentalkandy.spring.controllers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

/** PUT /api/me/password for customers and admins: the current password is checked, the new one hashed with BCrypt 12. */
@AutoConfigureMockMvc
@Transactional
class ChangePasswordTests extends PostgresIntegrationTest {

    private static final String CURRENT = "current-pass-1";
    private static final String NEW = "brand-new-pass-2";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtService jwt;

    private User saveUser(Role role) {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        User u = new User();
        u.setEmail("pw." + id + "@example.test");
        u.setPasswordHash(encoder.encode(CURRENT));
        u.setFullName("Password Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(TestNics.of("pw" + id));
        u.setRole(role);
        u.setIsActive(true);
        return users.saveAndFlush(u);
    }

    private ResultActions change(User user, String current, String next) throws Exception {
        return mvc.perform(put("/api/me/password").header(HttpHeaders.AUTHORIZATION, "Bearer " + jwt.issue(user))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(current, next)));
    }

    private ResultActions login(User user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(user.getEmail(), password)));
    }

    @Test
    void aCustomerChangesTheirPasswordAndSignsInWithTheNewOne() throws Exception {
        User customer = saveUser(Role.USER);
        change(customer, CURRENT, NEW).andExpect(status().isNoContent());

        String hash = users.findById(customer.getUserId()).orElseThrow().getPasswordHash();
        assertThat(hash).startsWith("$2a$12$").doesNotContain(NEW);
        login(customer, NEW).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty());
        login(customer, CURRENT).andExpect(status().isUnauthorized());
    }

    @Test
    void anAdminCanChangeTheirOwnPasswordToo() throws Exception {
        User admin = saveUser(Role.ADMIN);
        change(admin, CURRENT, NEW).andExpect(status().isNoContent());
        login(admin, NEW).andExpect(status().isOk());
    }

    @Test
    void aWrongCurrentPasswordIs400AndChangesNothing() throws Exception {
        User customer = saveUser(Role.USER);
        String before = customer.getPasswordHash();
        change(customer, "not-my-password", NEW)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The current password is incorrect"));
        assertThat(users.findById(customer.getUserId()).orElseThrow().getPasswordHash()).isEqualTo(before);
        login(customer, CURRENT).andExpect(status().isOk());
    }

    @Test
    void anInvalidNewPasswordIs400() throws Exception {
        User customer = saveUser(Role.USER);
        change(customer, CURRENT, "short7!").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        change(customer, CURRENT, "p".repeat(73)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.newPassword").exists());
        change(customer, "", NEW).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors.currentPassword").exists());
        login(customer, CURRENT).andExpect(status().isOk());
    }

    @Test
    void theNewPasswordMustDifferFromTheCurrentOne() throws Exception {
        change(saveUser(Role.USER), CURRENT, CURRENT)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("The new password must be different from the current password"));
    }

    @Test
    void signingInIsRequired() throws Exception {
        mvc.perform(put("/api/me/password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\": \"%s\", \"newPassword\": \"%s\"}".formatted(CURRENT, NEW)))
                .andExpect(status().isUnauthorized());
    }
}
