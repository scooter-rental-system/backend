package com.scooterrentalkandy.spring.services;

import java.util.Locale;
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

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    /** Checked against when the email is unknown, so that case costs the same BCrypt work as a wrong password. */
    private final String unknownUserHash;

    public AuthService(UserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.unknownUserHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthResponse register(RegisterRequest req) {
        String email = normalizeEmail(req.email());
        String nic = req.nicNumber().trim();
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
