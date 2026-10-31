package com.scooterrentalkandy.spring.support;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.scooterrentalkandy.spring.entity.Booking;
import com.scooterrentalkandy.spring.entity.User;
import com.scooterrentalkandy.spring.entity.enums.BookingStatus;

/**
 * Fixture state for bookings saved directly by tests, matching what the application records when a rental starts
 * (and what the V22 constraints require): an ACTIVE or COMPLETED rental has an actual start, and a handover is
 * recorded whole, with the admin and the starting odometer.
 */
public final class Rentals {

    /** The starting odometer used by fixtures that do not care about the reading. */
    public static final BigDecimal START_ODOMETER_KM = new BigDecimal("1000.0");

    private Rentals() {
    }

    /**
     * Marks {@code b} as a rental that started at {@code start}: handed over by {@code admin} with the odometer at
     * {@code startOdometerKm}, then confirmed by the customer. Sets the status to ACTIVE when it is not yet started.
     */
    public static Booking started(Booking b, LocalDateTime start, User admin, BigDecimal startOdometerKm) {
        b.setHandoverConfirmedAt(start);
        b.setHandoverConfirmedBy(admin);
        b.setStartOdometerKm(startOdometerKm);
        b.setCustomerStartConfirmedAt(start);
        b.setActualStartTime(start);
        if (b.getStatus() == null || b.getStatus() == BookingStatus.PENDING
                || b.getStatus() == BookingStatus.READY_FOR_PICKUP) {
            b.setStatus(BookingStatus.ACTIVE);
        }
        return b;
    }

    /**
     * Gives {@code b} an actual start at {@code start} only, with no handover or odometer: what V22 backfills for
     * rentals started before the pickup step existed (their distance is billed from GPS).
     */
    public static Booking startedAt(Booking b, LocalDateTime start) {
        b.setActualStartTime(start);
        return b;
    }

    /** Records an admin handover on a READY_FOR_PICKUP fixture, without the customer's start confirmation. */
    public static Booking handedOver(Booking b, LocalDateTime at, User admin, BigDecimal startOdometerKm) {
        b.setHandoverConfirmedAt(at);
        b.setHandoverConfirmedBy(admin);
        b.setStartOdometerKm(startOdometerKm);
        return b;
    }
}
