package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.UserDtos.AuthResponse;
import com.scooterrentalkandy.spring.dto.UserDtos.LoginRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.RegisterRequest;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import com.scooterrentalkandy.spring.support.PostgresIntegrationTest;
import com.scooterrentalkandy.spring.support.TestNics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * AuthService against the real schema: a register/login round trip, and the unique constraints reported as 409
 * when a concurrent registration slips past the duplicate checks. Each test rolls back.
 */
@Transactional
class AuthServiceDatabaseTests extends PostgresIntegrationTest {

    @Autowired
    private AuthService auth;

    @Autowired
    private UserRepository users;

    @Autowired
    private PasswordEncoder encoder;

    @Autowired
    private JwtService jwt;

    @Autowired
    private NotificationService notifications;

    private static RegisterRequest registration(String email, String nic) {
        return new RegisterRequest(email, "secret-pass-1", "Race Rider", "+94 70 000 0000", TestNics.of(nic), "", "", "",
                true);
    }

    private static String unique() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** The real repository, except that the duplicate checks see nothing, as in a lost race. */
    private AuthService racingAuthService() {
        UserRepository racing = (UserRepository) Proxy.newProxyInstance(UserRepository.class.getClassLoader(),
                new Class<?>[] {UserRepository.class}, (proxy, method, args) -> {
                    if (method.getName().equals("existsByEmailIgnoreCase")
                            || method.getName().equals("existsByNicNumber")) {
                        return false;
                    }
                    try {
                        return method.invoke(users, args);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        return new AuthService(racing, encoder, jwt, notifications);
    }

    @Test
    void registeredUserCanLogInWithTheEmailInAnyCase() {
        String id = unique();
        AuthResponse registered = auth.register(registration("Round.Trip." + id + "@Example.Test", "R" + id));

        assertThat(users.findById(registered.user().id())).get()
                .satisfies(u -> {
                    assertThat(u.getEmail()).isEqualTo("round.trip." + id + "@example.test");
                    assertThat(u.getNicNumber()).isEqualTo(TestNics.of("R" + id));
                    assertThat(u.getRole()).isEqualTo(Role.USER);
                    assertThat(encoder.matches("secret-pass-1", u.getPasswordHash())).isTrue();
                });
        AuthResponse loggedIn = auth.login(new LoginRequest("ROUND.TRIP." + id + "@example.test", "secret-pass-1"));
        assertThat(loggedIn.user()).isEqualTo(registered.user());
        assertThat(jwt.parse(loggedIn.token())).get()
                .satisfies(c -> assertThat(c.getSubject()).isEqualTo(registered.user().id().toString()));
    }

    @Test
    void emailUniqueConstraintIsReportedAsAConflict() {
        String id = unique();
        auth.register(registration("race." + id + "@example.test", "E" + id));

        assertThatThrownBy(() -> racingAuthService().register(registration("RACE." + id + "@example.test", "F" + id)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo(AuthService.DUPLICATE_EMAIL);
                });
    }

    @Test
    void nicUniqueConstraintIsReportedAsAConflict() {
        String id = unique();
        auth.register(registration("first." + id + "@example.test", "N" + id));

        assertThatThrownBy(() -> racingAuthService().register(registration("second." + id + "@example.test", "N" + id)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo(AuthService.DUPLICATE_NIC);
                });
    }
}
