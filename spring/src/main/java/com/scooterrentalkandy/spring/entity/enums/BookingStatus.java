package com.scooterrentalkandy.spring.entity.enums;

import java.util.List;

/** SDS 3.2 BookingStatus: PENDING → ACTIVE → COMPLETED, or CANCELLED. */
public enum BookingStatus {
    /** Created, contract may be signed, waiting for payment. Holds the scooter for a short time. */
    PENDING,
    /** Paid with the contract signed. The rental starts when the scooter is handed over (checkedOutAt). */
    ACTIVE,
    /** Scooter returned and the rental settled. */
    COMPLETED,
    CANCELLED;

    /** Statuses that reserve the scooter and gear for the booked dates. */
    public static final List<BookingStatus> BLOCKING = List.of(PENDING, ACTIVE);
}
