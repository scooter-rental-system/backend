package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.GearItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GearItemRepository extends JpaRepository<GearItem, UUID> {

    /** Row lock used to serialise concurrent bookings that reserve the same gear item. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select g from GearItem g where g.gearItemId = :id")
    Optional<GearItem> findByIdForUpdate(UUID id);

    List<GearItem> findByActiveTrueOrderByCategoryAscNameAsc();

    List<GearItem> findAllByOrderByCategoryAscNameAsc();
}
