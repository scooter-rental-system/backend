package com.scooterrentalkandy.spring.services;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.dto.UserDtos.AuthResponse;
import com.scooterrentalkandy.spring.dto.UserDtos.ChangePasswordRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.LoginRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.RegisterRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UpdateProfileRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UserResponse;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import com.scooterrentalkandy.spring.exception.ApiException;
import com.scooterrentalkandy.spring.repository.UserRepository;
import com.scooterrentalkandy.spring.security.JwtService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer registration, login and the signed-in user's own profile. Emails are stored and looked up trimmed and
 * lower-cased; blank optional fields are stored as null. Self-registration always creates a USER.
 */
@Service
public class AuthService {

    static final String DUPLICATE_EMAIL = "An account with this email already exists";
    static final String DUPLICATE_NIC = "An account with this NIC number already exists";
    static final String ACCOUNT_DEACTIVATED = "This account has been deactivated";
    static final String WELCOME_SUBJECT = "Welcome to Scooter Rent Kandy";
    static final String CURRENT_PASSWORD_WRONG = "The current password is incorrect";
    static final String SAME_PASSWORD = "The new password must be different from the current password";
    static final String PASSWORD_CHANGED_SUBJECT = "Your password was changed";

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final NotificationService notifications;
    /** Checked against when the email is unknown, so that case costs the same BCrypt work as a wrong password. */
    private final String unknownUserHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService,
                       NotificationService notifications) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.notifications = notifications;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        // Old-format NICs end in V or X; stored upper-case so the same number is always the same NIC.
        String nic = req.nicNumber().trim().toUpperCase(Locale.ROOT);
        if (users.existsByEmailIgnoreCase(email)) {
            throw ApiException.conflict(DUPLICATE_EMAIL);
        }
        if (users.existsByNicNumber(nic)) {
            throw ApiException.conflict(DUPLICATE_NIC);
        }
        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(req.password()));
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone().trim());
        user.setNicNumber(nic);
        user.setIdDocumentNumber(blankToNull(req.idDocumentNumber()));
        user.setDrivingLicenseNo(blankToNull(req.drivingLicenseNo()));
        user.setCountry(blankToNull(req.country()));
        user.setRole(Role.USER);
        user.setIsActive(true);
        try {
            user = users.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            // The checks above can race with a concurrent registration; the unique constraints decide.
            throw duplicateOrRethrow(ex);
        }
        // SDS 4.1: welcome in-app, and by email once the registration has committed (when email is configured).
        notifications.notify(user, WELCOME_SUBJECT, "Hi " + user.getFullName() + ", your account is ready. "
                + "Browse the scooters available in Kandy and book one whenever you are ready to ride.");
        return new AuthResponse(jwtService.issue(user), UserResponse.from(user));
    }

    /**
     * An unknown email and a wrong password fail identically (401 "Invalid email or password"). A deactivated
     * account is reported only after the correct password, so it does not reveal which emails are registered.
     */
    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest req) {
        Optional<User> found = users.findByEmailIgnoreCase(normalizeEmail(req.email()));
        String hash = found.map(User::getPasswordHash).orElse(unknownUserHash);
        boolean matches = passwordEncoder.matches(req.password(), hash);
        User user = found.filter(u -> matches).orElseThrow(() -> new BadCredentialsException("Bad credentials"));
        if (!Boolean.TRUE.equals(user.getIsActive())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, ACCOUNT_DEACTIVATED);
        }
        return new AuthResponse(jwtService.issue(user), UserResponse.from(user));
    }

    /** The signed-in user; {@code userId} is the JWT subject, available as {@code AuthUser.id()}. */
    @Transactional(readOnly = true)
    public UserResponse currentUser(UUID userId) {
        return UserResponse.from(find(userId));
    }

    /** Updates only the fields in {@link UpdateProfileRequest}; email, NIC, role, password and status are kept. */
    @Transactional
    public UserResponse updateProfile(UUID userId, UpdateProfileRequest req) {
        User user = find(userId);
        user.setFullName(req.fullName().trim());
        user.setPhone(req.phone().trim());
        user.setIdDocumentNumber(blankToNull(req.idDocumentNumber()));
        user.setDrivingLicenseNo(blankToNull(req.drivingLicenseNo()));
        user.setCountry(blankToNull(req.country()));
        return UserResponse.from(users.saveAndFlush(user));
    }

    /**
     * Changes the signed-in user's password after checking the current one. A wrong current password is a 400 (not a
     * 401: the session is valid). The new hash uses the application's PasswordEncoder (BCrypt cost 12).
     */
    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User user = find(userId);
        if (!passwordEncoder.matches(req.currentPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest(CURRENT_PASSWORD_WRONG);
        }
        if (passwordEncoder.matches(req.newPassword(), user.getPasswordHash())) {
            throw ApiException.badRequest(SAME_PASSWORD);
        }
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        users.saveAndFlush(user);
        notifications.notify(user, PASSWORD_CHANGED_SUBJECT, "Your Scooter Rent Kandy password was changed. If this was "
                + "not you, contact us straight away.");
    }

    private User find(UUID userId) {
        return users.findById(userId).orElseThrow(() -> ApiException.notFound("User"));
    }

    private static RuntimeException duplicateOrRethrow(DataIntegrityViolationException ex) {
        String detail = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase(Locale.ROOT);
        if (detail.contains("users_email_key")) {
            return ApiException.conflict(DUPLICATE_EMAIL);
        }
        if (detail.contains("uq_users_nic")) {
            return ApiException.conflict(DUPLICATE_NIC);
        }
        return ex;
    }

    static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
