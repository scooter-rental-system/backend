package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.common.enums.UserRole;
import com.scooterrentalkandy.spring.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    /** Find a user by email (used during login and duplicate-email validation). */
    Optional<User> findByEmail(String email);

    /** Check whether an email address is already registered. */
    boolean existsByEmail(String email);

    /** Check whether a NIC number is already registered. */
    boolean existsByNicNumber(String nicNumber);

    /** Fetch all users with a specific role (admin management). */
    List<User> findByRole(UserRole role);

    /** Fetch all active / inactive users (admin account management). */
    List<User> findByIsActive(Boolean isActive);
}
