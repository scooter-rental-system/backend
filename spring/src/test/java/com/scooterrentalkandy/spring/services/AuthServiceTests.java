package com.scooterrentalkandy.spring.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.UserDtos.AuthResponse;
import com.scooterrentalkandy.spring.dto.UserDtos.LoginRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.RegisterRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UpdateProfileRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UserResponse;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** AuthService business rules with a mocked repository, encoder and JWT service. */
class AuthServiceTests {

    private static final String TOKEN = "issued-jwt";
    private static final String UNKNOWN_USER_HASH = "hash-for-unknown-users";

    private UserRepository users;
    private PasswordEncoder encoder;
    private JwtService jwt;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        encoder = mock(PasswordEncoder.class);
        jwt = mock(JwtService.class);
        when(encoder.encode(anyString())).thenReturn(UNKNOWN_USER_HASH);
        when(encoder.encode("secret-pass-1")).thenReturn("bcrypt(secret-pass-1)");
        when(jwt.issue(any(User.class))).thenReturn(TOKEN);
        when(users.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            if (u.getUserId() == null) {
                u.setUserId(UUID.randomUUID());
            }
            return u;
        });
        auth = new AuthService(users, encoder, jwt);
    }

    private static RegisterRequest registration() {
        return new RegisterRequest("  New.Customer@Example.TEST ", "secret-pass-1", "  New Customer ",
                " +94 70 000 0000 ", "  200012345678 ", "", "  ", "  Sri Lanka ");
    }

    private User savedUser() {
        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(users).saveAndFlush(saved.capture());
        return saved.getValue();
    }

    private static User existing(boolean active) {
        User u = new User();
        u.setUserId(UUID.randomUUID());
        u.setEmail("rider@example.test");
        u.setPasswordHash("stored-hash");
        u.setFullName("Rider");
        u.setPhone("+94 70 111 1111");
        u.setNicNumber("199912345678");
        u.setIdDocumentNumber("P1234567");
        u.setDrivingLicenseNo("B7654321");
        u.setCountry("Sri Lanka");
        u.setRole(Role.USER);
        u.setIsActive(active);
        u.setCreatedAt(LocalDateTime.of(2026, 1, 1, 9, 0));
        return u;
    }

    // ── Register ──────────────────────────────────────────────────────────────

    @Test
    void registerStoresANormalizedActiveUserWithItsNicAndAnEncodedPassword() {
        auth.register(registration());

        User u = savedUser();
        assertThat(u.getEmail()).isEqualTo("new.customer@example.test");
        assertThat(u.getNicNumber()).isEqualTo("200012345678");
        assertThat(u.getPasswordHash()).isEqualTo("bcrypt(secret-pass-1)").isNotEqualTo("secret-pass-1");
        assertThat(u.getRole()).isEqualTo(Role.USER);
        assertThat(u.getIsActive()).isTrue();
        assertThat(u.getFullName()).isEqualTo("New Customer");
        assertThat(u.getPhone()).isEqualTo("+94 70 000 0000");
        assertThat(u.getCountry()).isEqualTo("Sri Lanka");
    }

    @Test
    void registerStoresBlankOptionalFieldsAsNull() {
        auth.register(registration());

        User u = savedUser();
        assertThat(u.getIdDocumentNumber()).isNull();
        assertThat(u.getDrivingLicenseNo()).isNull();
    }

    @Test
    void registerChecksDuplicatesWithTheNormalizedValues() {
        auth.register(registration());

        verify(users).existsByEmailIgnoreCase("new.customer@example.test");
        verify(users).existsByNicNumber("200012345678");
    }

    @Test
    void registerReturnsTheTokenAndTheUserWithoutPasswordHashOrNic() {
        AuthResponse response = auth.register(registration());

        User saved = savedUser();
        assertThat(response.token()).isEqualTo(TOKEN);
        verify(jwt).issue(saved);
        UserResponse user = response.user();
        assertThat(user.id()).isNotNull();
        assertThat(user.email()).isEqualTo("new.customer@example.test");
        assertThat(user.fullName()).isEqualTo("New Customer");
        assertThat(user.role()).isEqualTo(Role.USER);
        assertThat(user.active()).isTrue();
        assertThat(Arrays.stream(UserResponse.class.getRecordComponents()).map(c -> c.getName()))
                .doesNotContain("passwordHash", "password", "nicNumber");
        assertThat(user.toString()).doesNotContain("bcrypt(secret-pass-1)", "200012345678");
    }

    @Test
    void registerRejectsADuplicateEmailInAnyCase() {
        when(users.existsByEmailIgnoreCase("new.customer@example.test")).thenReturn(true);

        assertThatThrownBy(() -> auth.register(registration()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo(AuthService.DUPLICATE_EMAIL);
                });
        verify(users, never()).saveAndFlush(any());
        verify(jwt, never()).issue(any());
    }

    @Test
    void registerRejectsADuplicateNic() {
        when(users.existsByNicNumber("200012345678")).thenReturn(true);

        assertThatThrownBy(() -> auth.register(registration()))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(ex.getMessage()).isEqualTo(AuthService.DUPLICATE_NIC);
                });
        verify(users, never()).saveAndFlush(any());
        verify(jwt, never()).issue(any());
    }

    @Test
    void registerLetsUnrelatedIntegrityFailuresThrough() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("ck_users_role violated");
        when(users.saveAndFlush(any(User.class))).thenThrow(other);

        assertThatThrownBy(() -> auth.register(registration())).isSameAs(other);
        verify(jwt, never()).issue(any());
    }

    // ── Login ─────────────────────────────────────────────────────────────────

    @Test
    void loginWithTheRightPasswordReturnsATokenAndTheUser() {
        User rider = existing(true);
        when(users.findByEmailIgnoreCase("rider@example.test")).thenReturn(Optional.of(rider));
        when(encoder.matches("right-password", "stored-hash")).thenReturn(true);

        AuthResponse response = auth.login(new LoginRequest("  RIDER@Example.Test ", "right-password"));

        assertThat(response.token()).isEqualTo(TOKEN);
        assertThat(response.user()).isEqualTo(UserResponse.from(rider));
        verify(encoder).matches("right-password", "stored-hash");
        verify(jwt).issue(rider);
    }

    @Test
    void loginWithAWrongPasswordIsRejected() {
        when(users.findByEmailIgnoreCase("rider@example.test")).thenReturn(Optional.of(existing(true)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("rider@example.test", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
        verify(encoder).matches("wrong-password", "stored-hash");
        verify(jwt, never()).issue(any());
    }

    @Test
    void loginWithAnUnknownEmailFailsLikeAWrongPassword() {
        when(users.findByEmailIgnoreCase(anyString())).thenReturn(Optional.empty());

        Throwable unknown = catchThrowable(() -> auth.login(new LoginRequest("nobody@example.test", "any-password")));
        when(users.findByEmailIgnoreCase("rider@example.test")).thenReturn(Optional.of(existing(true)));
        Throwable wrong = catchThrowable(() -> auth.login(new LoginRequest("rider@example.test", "any-password")));

        assertThat(unknown).isInstanceOf(BadCredentialsException.class);
        assertThat(unknown.getClass()).isEqualTo(wrong.getClass());
        assertThat(unknown.getMessage()).isEqualTo(wrong.getMessage());
        // The unknown email still costs one password check, against a placeholder hash.
        verify(encoder).matches("any-password", UNKNOWN_USER_HASH);
        verify(jwt, never()).issue(any());
    }

    @Test
    void loginToADeactivatedAccountIsRejected() {
        when(users.findByEmailIgnoreCase("rider@example.test")).thenReturn(Optional.of(existing(false)));
        when(encoder.matches("right-password", "stored-hash")).thenReturn(true);

        assertThatThrownBy(() -> auth.login(new LoginRequest("rider@example.test", "right-password")))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
                    assertThat(ex.getMessage()).isEqualTo(AuthService.ACCOUNT_DEACTIVATED);
                });
        verify(jwt, never()).issue(any());
    }

    @Test
    void loginToADeactivatedAccountWithAWrongPasswordLooksLikeAnyBadLogin() {
        when(users.findByEmailIgnoreCase("rider@example.test")).thenReturn(Optional.of(existing(false)));

        assertThatThrownBy(() -> auth.login(new LoginRequest("rider@example.test", "wrong-password")))
                .isInstanceOf(BadCredentialsException.class);
    }

    // ── Current user and profile ──────────────────────────────────────────────

    @Test
    void currentUserIsLookedUpByTheJwtSubjectId() {
        User rider = existing(true);
        when(users.findById(rider.getUserId())).thenReturn(Optional.of(rider));

        assertThat(auth.currentUser(rider.getUserId())).isEqualTo(UserResponse.from(rider));
    }

    @Test
    void currentUserThatNoLongerExistsIsNotFound() {
        UUID id = UUID.randomUUID();
        when(users.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> auth.currentUser(id))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void updateProfileChangesOnlyTheEditableFields() {
        User rider = existing(true);
        rider.setRole(Role.ADMIN);
        when(users.findById(rider.getUserId())).thenReturn(Optional.of(rider));

        UserResponse response = auth.updateProfile(rider.getUserId(),
                new UpdateProfileRequest("  Renamed Rider ", " +94 77 222 2222 ", " ", "", "  India "));

        assertThat(rider.getFullName()).isEqualTo("Renamed Rider");
        assertThat(rider.getPhone()).isEqualTo("+94 77 222 2222");
        assertThat(rider.getIdDocumentNumber()).isNull();
        assertThat(rider.getDrivingLicenseNo()).isNull();
        assertThat(rider.getCountry()).isEqualTo("India");
        assertThat(rider.getEmail()).isEqualTo("rider@example.test");
        assertThat(rider.getNicNumber()).isEqualTo("199912345678");
        assertThat(rider.getRole()).isEqualTo(Role.ADMIN);
        assertThat(rider.getPasswordHash()).isEqualTo("stored-hash");
        assertThat(rider.getIsActive()).isTrue();
        assertThat(response).isEqualTo(UserResponse.from(rider));
        verify(jwt, never()).issue(any());
    }
}
