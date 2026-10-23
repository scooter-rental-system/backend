package com.scooterrentalkandy.spring.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class UserDtos {

    private UserDtos() {
    }

    /**
     * Customer self-registration. The NIC number is required and unique (SRS FR-001, V11); no NIC format is
     * enforced. The API currently limits passwords to 72 characters for compatibility with the project's BCrypt
     * usage (this counts Java characters, not UTF-8 bytes). Size limits follow the {@code users} columns.
     */
    public record RegisterRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 72, message = "must be between 8 and 72 characters") String password,
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Size(max = 20) String phone,
            @NotBlank @Size(max = 20) String nicNumber,
            @Size(max = 100) String idDocumentNumber,
            @Size(max = 100) String drivingLicenseNo,
            @Size(max = 100) String country) {
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
