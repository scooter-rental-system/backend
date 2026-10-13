package com.scooterrentalkandy.spring.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.Scooter;
import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface ScooterRepository extends JpaRepository<Scooter, UUID> {

    /** Row lock used to serialise concurrent bookings of the same scooter. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Scooter s where s.scooterId = :id")
    Optional<Scooter> findByIdForUpdate(UUID id);

    List<Scooter> findAllByOrderByCodeAsc();

    List<Scooter> findByDeletedFalseOrderByCodeAsc();

    List<Scooter> findByStatus(ScooterStatus status);

    Optional<Scooter> findByCodeIgnoreCase(String code);

    boolean existsByCodeIgnoreCase(String code);

    boolean existsByPlateNumberIgnoreCase(String plateNumber);

    long countByStatusAndDeletedFalse(ScooterStatus status);
}