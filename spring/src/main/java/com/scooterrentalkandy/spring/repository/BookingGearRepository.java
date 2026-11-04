package com.scooterrentalkandy.spring.repository;

import java.util.Collection;
import java.util.UUID;
import com.scooterrentalkandy.spring.entity.BookingGear;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BookingGearRepository extends JpaRepository<BookingGear, UUID> {

    @Query("""
            select coalesce(sum(bg.quantity), 0) from BookingGear bg
            where bg.gearItem.gearItemId = :gearItemId and bg.booking.status in :statuses
            """)
    long sumReservedQuantity(@Param("gearItemId") UUID gearItemId,
                             @Param("statuses") Collection<BookingStatus> statuses);
}