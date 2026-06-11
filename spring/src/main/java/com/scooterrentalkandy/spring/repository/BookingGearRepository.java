package com.scooterrentalkandy.spring.repository;

import com.scooterrentalkandy.spring.entity.BookingGear;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface BookingGearRepository extends JpaRepository<BookingGear, UUID> {

    /** All gear line-items for a specific booking. */
    List<BookingGear> findByBooking_BookingId(UUID bookingId);

    /** All bookings that include a specific gear item (inventory tracking). */
    List<BookingGear> findByCampingGear_GearId(UUID gearId);

    /** Remove all gear items from a booking (used on booking cancellation). */
    void deleteByBooking_BookingId(UUID bookingId);
}
