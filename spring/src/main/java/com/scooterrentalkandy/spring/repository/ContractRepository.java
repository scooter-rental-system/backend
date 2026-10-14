package com.scooterrentalkandy.spring.repository;

import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Contract;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContractRepository extends JpaRepository<Contract, UUID> {

    Optional<Contract> findByBookingId(UUID bookingId);
}
