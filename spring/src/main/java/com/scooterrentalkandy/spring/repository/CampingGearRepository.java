package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.CampingGear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CampingGearRepository extends JpaRepository<CampingGear, UUID> {

    /** Find all gear items that still have stock available. */
    List<CampingGear> findByStockGreaterThan(int minStock);

    /** Check availability of a specific gear item. */
    @Query("SELECT g.stock FROM CampingGear g WHERE g.gearId = :gearId")
    Long findStockByGearId(UUID gearId);
}
