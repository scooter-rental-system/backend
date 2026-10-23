package com.scooterrentalkandy.spring.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** The UserRepository lookups used by registration and login. Each test rolls back. */
@Transactional
class UserRepositoryAuthTests extends PostgresIntegrationTest {

    @Autowired
    private UserRepository users;

    private String email;
    private String nic;

    @BeforeEach
    void saveUser() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        email = "Mixed.Case." + unique.substring(0, 8) + "@Example.Test";
        nic = "T" + unique.substring(0, 19);
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("Auth Lookup User");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(nic);
        u.setRole(Role.USER);
        users.saveAndFlush(u);
    }

    @Test
    void findsAUserByEmailRegardlessOfCase() {
        assertThat(users.findByEmailIgnoreCase(email.toLowerCase())).get().extracting(User::getNicNumber).isEqualTo(nic);
        assertThat(users.findByEmailIgnoreCase(email.toUpperCase())).isPresent();
        assertThat(users.findByEmailIgnoreCase("nobody@example.test")).isEmpty();
    }

    @Test
    void checksEmailExistenceRegardlessOfCase() {
        assertThat(users.existsByEmailIgnoreCase(email.toLowerCase())).isTrue();
        assertThat(users.existsByEmailIgnoreCase(email.toUpperCase())).isTrue();
        assertThat(users.existsByEmailIgnoreCase("nobody@example.test")).isFalse();
    }

    @Test
    void checksNicExistence() {
        assertThat(users.existsByNicNumber(nic)).isTrue();
        assertThat(users.existsByNicNumber("T-UNUSED-NIC-0001")).isFalse();
    }
}
