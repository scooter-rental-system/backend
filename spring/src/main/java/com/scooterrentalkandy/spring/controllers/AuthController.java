package com.scooterrentalkandy.spring.controllers;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import com.scooterrentalkandy.spring.dto.UserDtos.AuthResponse;
import com.scooterrentalkandy.spring.dto.UserDtos.ChangePasswordRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.LoginRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.RegisterRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UpdateProfileRequest;
import com.scooterrentalkandy.spring.dto.UserDtos.UserResponse;
import com.scooterrentalkandy.spring.security.AuthUser;
import com.scooterrentalkandy.spring.exception.LoginRateLimitedException;
import com.scooterrentalkandy.spring.security.CurrentUser;
import com.scooterrentalkandy.spring.security.LoginRateLimiter;
import com.scooterrentalkandy.spring.services.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Registration and login are public; {@code /api/me} is the signed-in user, identified by the JWT. Failed logins are
 * limited per client address (SDS 6.1, {@link LoginRateLimiter}).
 */
@RestController
public class AuthController {

    private final AuthService auth;
    private final LoginRateLimiter loginLimiter;

    public AuthController(AuthService auth, LoginRateLimiter loginLimiter) {
        this.auth = auth;
        this.loginLimiter = loginLimiter;
    }

    @PostMapping("/api/auth/register")
    @ResponseStatus(HttpStatus.CREATED)
    public AuthResponse register(@Valid @RequestBody RegisterRequest req) {
        return auth.register(req);
    }

    @PostMapping("/api/auth/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest request) {
        // The client address; under the prod profile Tomcat resolves it from X-Forwarded-For set by trusted proxies.
        String address = request.getRemoteAddr();
        loginLimiter.blockedFor(address).ifPresent(wait -> {
            throw new LoginRateLimitedException(wait);
        });
        try {
            return auth.login(req);
        } catch (BadCredentialsException ex) {
            loginLimiter.recordFailure(address);
            throw ex;
        }
    }

    @GetMapping("/api/me")
    public UserResponse me(@CurrentUser AuthUser user) {
        return auth.currentUser(user.id());
    }

    @PutMapping("/api/me")
    public UserResponse updateMe(@CurrentUser AuthUser user, @Valid @RequestBody UpdateProfileRequest req) {
        return auth.updateProfile(user.id(), req);
    }

    /** Any signed-in user (customer or admin) changes their own password; 204 on success. */
    @PutMapping("/api/me/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(@CurrentUser AuthUser user, @Valid @RequestBody ChangePasswordRequest req) {
        auth.changePassword(user.id(), req);
    }
}
