package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.GearItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface GearItemRepository extends JpaRepository<GearItem, UUID> {

    List<GearItem> findByActiveTrueOrderByCategoryAscNameAsc();

    List<GearItem> findAllByOrderByCategoryAscNameAsc();
}
