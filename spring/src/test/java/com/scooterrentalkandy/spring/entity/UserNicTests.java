package com.scooterrentalkandy.spring.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.UUID;
import java.util.function.Predicate;
import javax.sql.DataSource;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code User.nicNumber} against the migrated schema ({@code nic_number VARCHAR(20) NOT NULL}, unique constraint
 * {@code uq_users_nic}). Bean Validation rejects null, blank and over-length values before the insert; the database
 * constraints are checked separately with plain JDBC, which bypasses Bean Validation. No NIC format is enforced.
 * All NICs here are random synthetic values; each test rolls back.
 */
@Transactional
class UserNicTests extends PostgresIntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private DataSource dataSource;

    @Test
    void persistsAndReadsBackAValidNic() {
        String nic = syntheticNic();
        User saved = users.saveAndFlush(newUser(nic));

        assertThat(users.findById(saved.getUserId())).get().extracting(User::getNicNumber).isEqualTo(nic);
        assertThat(jdbc().queryForObject("SELECT nic_number FROM users WHERE user_id = ?", String.class,
                saved.getUserId())).isEqualTo(nic);
    }

    @Test
    void acceptsANicOfExactlyTwentyCharacters() {
        String nic = "T" + "1".repeat(19);
        assertThat(users.saveAndFlush(newUser(nic)).getNicNumber()).hasSize(20);
    }

    @Test
    void validationRejectsAMissingNic() {
        assertThatThrownBy(() -> users.saveAndFlush(newUser(null)))
                .satisfies(thrown -> assertThat(nicViolation(thrown)).as("NotBlank violation on nicNumber").isTrue());
    }

    @Test
    void validationRejectsABlankNic() {
        assertThatThrownBy(() -> users.saveAndFlush(newUser("   ")))
                .satisfies(thrown -> assertThat(nicViolation(thrown)).as("NotBlank violation on nicNumber").isTrue());
    }

    @Test
    void validationRejectsANicLongerThanTwentyCharacters() {
        assertThatThrownBy(() -> users.saveAndFlush(newUser("T" + "1".repeat(20))))
                .satisfies(thrown -> assertThat(nicViolation(thrown)).as("Size violation on nicNumber").isTrue());
    }

    @Test
    void databaseRejectsANullNic() {
        assertThatThrownBy(() -> insertWithJdbc(null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(hasSqlState(thrown, "23502")).as("not-null violation").isTrue())
                .hasMessageContaining("nic_number");
    }

    @Test
    void databaseRejectsANicLongerThanTwentyCharacters() {
        assertThatThrownBy(() -> insertWithJdbc("T" + "1".repeat(20)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(thrown -> assertThat(hasSqlState(thrown, "22001")).as("value too long").isTrue());
    }

    @Test
    void databaseRejectsADuplicateNic() {
        String nic = syntheticNic();
        users.saveAndFlush(newUser(nic));

        assertThatThrownBy(() -> users.saveAndFlush(newUser(nic)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_users_nic");
    }

    private static User newUser(String nic) {
        String unique = UUID.randomUUID().toString();
        User u = new User();
        u.setEmail(unique + "@example.test");
        u.setPasswordHash("not-a-real-hash");
        u.setFullName("NIC Test User");
        u.setPhone("+94 70 000 0000");
        u.setNicNumber(nic);
        u.setRole(Role.USER);
        return u;
    }

    private void insertWithJdbc(String nic) {
        jdbc().update("INSERT INTO users (user_id, email, password_hash, full_name, phone, role, nic_number, "
                + "created_at) VALUES (?, ?, 'not-a-real-hash', 'NIC Test User', '+94 70 000 0000', 'USER', ?, now())",
                UUID.randomUUID(), UUID.randomUUID() + "@example.test", nic);
    }

    /** A random 20-character value that cannot be a real NIC. */
    private static String syntheticNic() {
        return "T" + UUID.randomUUID().toString().replace("-", "").substring(0, 19);
    }

    private static boolean nicViolation(Throwable thrown) {
        return anyCause(thrown, t -> t instanceof ConstraintViolationException violations
                && violations.getConstraintViolations().stream()
                .anyMatch(v -> v.getPropertyPath().toString().equals("nicNumber")));
    }

    private static boolean hasSqlState(Throwable thrown, String sqlState) {
        return anyCause(thrown, t -> t instanceof SQLException sql && sqlState.equals(sql.getSQLState()));
    }

    private static boolean anyCause(Throwable thrown, Predicate<Throwable> test) {
        for (Throwable t = thrown; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (test.test(t)) {
                return true;
            }
        }
        return false;
    }

    private JdbcTemplate jdbc() {
        return new JdbcTemplate(dataSource);
    }
}
