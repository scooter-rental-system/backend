package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import com.scooterrentalkandy.spring.entity.Scooter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ScooterRepository extends JpaRepository<Scooter, UUID> {

    /** Find a scooter by its official registration plate number. */
    Optional<Scooter> findByRegistrationNo(String registrationNo);

    /** Check whether a registration number already exists in the fleet. */
    boolean existsByRegistrationNo(String registrationNo);

    /** Fetch all scooters with a given fleet status (e.g., AVAILABLE). */
    List<Scooter> findByStatus(ScooterStatus status);
}