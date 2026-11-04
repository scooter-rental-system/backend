package com.scooterrentalkandy.spring.security;

import java.util.UUID;
import com.scooterrentalkandy.spring.entity.enums.Role;

/** The authenticated principal stored in the SecurityContext. */
public record AuthUser(UUID id, String email, Role role) {

    public boolean isAdmin() {
        return role.isAdmin();
    }
}
