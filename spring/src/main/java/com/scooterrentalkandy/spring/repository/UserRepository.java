package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.Role;
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

    /** Login lookup; email addresses are matched without regard to case. */
    Optional<User> findByEmailIgnoreCase(String email);

    /** Duplicate-email check at registration, without regard to case. */
    boolean existsByEmailIgnoreCase(String email);

    /** Duplicate-NIC check at registration ({@code nic_number} is unique, V11). */
    boolean existsByNicNumber(String nicNumber);

    List<User> findByRoleOrderByCreatedAtDesc(Role role);

    List<User> findByRoleAndIsActiveTrue(Role role);

    List<User> findByRoleInAndIsActiveTrue(java.util.Collection<Role> roles);

    long countByRole(Role role);

    long countByRoleAndCreatedAtBetween(Role role, java.time.LocalDateTime from, java.time.LocalDateTime to);
}