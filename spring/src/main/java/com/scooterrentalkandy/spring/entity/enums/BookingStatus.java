package com.scooterrentalkandy.spring.entity.enums;

import java.util.List;

/**
 * SDS 3.2 BookingStatus, with the pickup step: PENDING → READY_FOR_PICKUP → ACTIVE → COMPLETED, or CANCELLED.
 */
public enum BookingStatus {
    /** Created, contract may be signed, waiting for payment. Holds the scooter for a short time. */
    PENDING,
    /**
     * Paid with the contract signed, waiting for the scooter to be handed over. Holds the scooter; the rental has
     * not started and nothing is billed for this time.
     */
    READY_FOR_PICKUP,
    /**
     * The rental has started: an admin confirmed the handover with the starting odometer and the customer confirmed
     * the start, at the server-recorded actualStartTime.
     */
    ACTIVE,
    /** Scooter returned and the rental settled. */
    COMPLETED,
    CANCELLED;

    /** Statuses that reserve the scooter and gear. */
    public static final List<BookingStatus> BLOCKING = List.of(PENDING, READY_FOR_PICKUP, ACTIVE);
}
