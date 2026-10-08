package com.scooterrentalkandy.spring.entity.enums;

import java.util.List;

/** SDS 3.2 UserRole. SUPER_ADMIN has every ADMIN permission; the SDS defines nothing beyond that. */
public enum Role {
    USER,
    ADMIN,
    SUPER_ADMIN;

    public static final List<Role> ADMINS = List.of(ADMIN, SUPER_ADMIN);

    public boolean isAdmin() {
        return this == ADMIN || this == SUPER_ADMIN;
    }
}

