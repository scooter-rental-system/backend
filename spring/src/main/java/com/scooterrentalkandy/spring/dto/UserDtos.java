package com.scooterrentalkandy.spring.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    /**
     * SDS 6.3: a Sri Lankan NIC number, old format (9 digits then V or X, either case) or new format (12 digits).
     * Surrounding spaces are allowed here because registration trims the value before storing it.
     */
    public static final String SRI_LANKAN_NIC = "^\\s*(\\d{9}[VvXx]|\\d{12})\\s*$";
    static final String NIC_MESSAGE = "must be a Sri Lankan NIC: 9 digits followed by V or X, or 12 digits";
    static final String CONSENT_MESSAGE = "must be accepted to create an account";

    /**
     * Customer self-registration. The NIC number is required, unique (SRS FR-001, V11) and in a Sri Lankan NIC format
     * (SDS 6.3). {@code consent} must be true: personal data is collected only with explicit consent (SDS 8.5). The API currently limits passwords to 72 characters for compatibility with the project's BCrypt
     * usage (this counts Java characters, not UTF-8 bytes). Size limits follow the {@code users} columns.
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank @Pattern(regexp = SRI_LANKAN_NIC, message = NIC_MESSAGE) String nicNumber,
            @Size(max = 100) String idDocumentNumber,
            @Size(max = 100) String drivingLicenseNo,
            @Size(max = 100) String country,
            @NotNull(message = CONSENT_MESSAGE) @AssertTrue(message = CONSENT_MESSAGE) Boolean consent) {
    }

    /**
     * The signed-in user's own password change. The current password is required; the new one follows the same rule
     * as registration (8 to 72 characters). Confirmation is checked by the form.
     */
    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String newPassword) {
    }

    /** Forgot password: the account email. The response is the same whether or not an account exists. */
    public record ForgotPasswordRequest(@NotBlank @Email @Size(max = 255) String email) {
    }

    /** Reset password from an emailed one-time link; the new password follows the registration rule (8 to 72). */
    public record ResetPasswordRequest(
            @NotBlank @Size(max = 200) String token,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String newPassword) {
    }

    /** A plain message for the public password-reset endpoints. */
    public record MessageResponse(String message) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    /** Successful register or login: the JWT and the signed-in user. */
    public record AuthResponse(String token, UserResponse user) {
    }

    /** Profile fields a user may change themselves; email, NIC, role and password are not editable here. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(max = 20) String phone,
            @Size(max = 100) String idDocumentNumber,
            @Size(max = 100) String drivingLicenseNo,
            @Size(max = 100) String country) {
    }

    /** A user as returned by the API; never includes the password hash or the NIC number. */
    public record UserResponse(
            UUID id,
            String email,
            String fullName,
            String phone,
            Role role,
            String idDocumentNumber,
            String drivingLicenseNo,
            String country,
            boolean active,
            LocalDateTime createdAt) {

        public static UserResponse from(User u) {
            return new UserResponse(u.getUserId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole(),
                    u.getIdDocumentNumber(), u.getDrivingLicenseNo(), u.getCountry(),
                    Boolean.TRUE.equals(u.getIsActive()), u.getCreatedAt());
        }
    }
}
