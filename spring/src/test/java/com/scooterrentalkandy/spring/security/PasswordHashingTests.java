package com.scooterrentalkandy.spring.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-035 (SDS 6.2 / 8.5): new passwords are hashed with BCrypt cost 12, and accounts whose hashes were made at the
 * previous default cost 10 still sign in unchanged (no rehash, no migration).
 */
@AutoConfigureMockMvc
@Transactional
class PasswordHashingTests extends PostgresIntegrationTest {

    private static final String PASSWORD = "secret-pass-1";

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private UserRepository users;

    @Autowired
    private MockMvc mvc;

    @Test
    void hashesNewPasswordsWithCost12() {
        assertThat(SecurityConfig.BCRYPT_STRENGTH).isEqualTo(12);
        String hash = encoder.encode(PASSWORD);
        assertThat(hash).startsWith("$2a$12$");
        assertThat(encoder.matches(PASSWORD, hash)).isTrue();
        assertThat(encoder.matches("wrong-pass-1", hash)).isFalse();
    }

    @Test
    void stillVerifiesHashesMadeAtTheFormerCost10() {
        String legacy = new BCryptPasswordEncoder(10).encode(PASSWORD);
        assertThat(legacy).startsWith("$2a$10$");
        assertThat(encoder.matches(PASSWORD, legacy)).isTrue();
        assertThat(encoder.matches("wrong-pass-1", legacy)).isFalse();
    }

    @Test
    void anAccountWithACost10HashSignsInAndKeepsItsHash() throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String legacy = new BCryptPasswordEncoder(10).encode(PASSWORD);
        User u = new User();
        u.setEmail("legacy." + id + "@example.test");
        u.setPasswordHash(legacy);
        u.setFullName("Legacy Rider");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber("L" + id);
        u.setRole(Role.USER);
        u.setIsActive(true);
        users.saveAndFlush(u);

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"%s\"}".formatted(u.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\": \"%s\", \"password\": \"wrong-pass-1\"}".formatted(u.getEmail())))
                .andExpect(status().isUnauthorized());

        assertThat(users.findByEmailIgnoreCase(u.getEmail()).orElseThrow().getPasswordHash()).isEqualTo(legacy);
    }
}
