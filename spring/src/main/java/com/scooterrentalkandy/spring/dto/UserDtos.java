package com.scooterrentalkandy.spring.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;

public final class UserDtos {

    private UserDtos() {
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
