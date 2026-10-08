package com.scooterrentalkandy.spring.entity.enums;

/** SDS 3.2 ScooterStatus. Scooters leaving the fleet are soft-deleted rather than given a status (SDS 8.3). */
public enum ScooterStatus {
    /** In the yard, can be booked. */
    AVAILABLE,
    /** Currently out with a customer. */
    RENTED,
    /** In the workshop; cannot be checked out. */
    MAINTENANCE
}