package com.scooterrentalkandy.spring.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * Junction (weak) entity implementing the M:N relationship between
 * {@link Booking} and {@link CampingGear}, carrying a {@code quantity} attribute.
 */
@Entity
@Table(
        name = "booking_gears",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_booking_gear",
                        columnNames = {"booking_id", "gear_id"}
                )
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingGear {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "booking_gear_id", nullable = false, updatable = false)
    private UUID bookingGearId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private CampingGear campingGear;

    /** Number of units of this gear item included in the booking. */
    @Column(name = "quantity", nullable = false)
    private Integer quantity;
}
