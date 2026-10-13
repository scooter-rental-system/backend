package com.scooterrentalkandy.spring.entity;

import com.scooterrentalkandy.spring.entity.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
        name = "bookings",
        indexes = {
                // Partial unique index concept: enforced at DB level via migration script.
                // Only one ACTIVE booking per scooter at any time.
                @Index(name = "idx_bookings_scooter_id", columnList = "scooter_id"),
                @Index(name = "idx_bookings_user_id",    columnList = "user_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(nullable = false, unique = true)
    private String reference;

    // ── Foreign Keys ───────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scooter_id", nullable = false)
    private Scooter scooter;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    // ── Attributes ─────────────────────────────────────────────────────────────

    @Column(name = "start_time", nullable = false)
    private LocalDateTime startTime;

    /** NULL while rental is still active. */
    @Column(name = "end_time")
    private LocalDateTime endTime;

    /** Distance from the booking's GPS logs; set when the booking is COMPLETED. */
    @Column(name = "distance_km", precision = 10, scale = 2)
    private BigDecimal distanceKm;

    /** SDS 2.2: final cost; NULL until the booking is COMPLETED. */
    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost;

    /**
     * Frozen settlement, set at the first attempt to end the rental. If the remaining-balance charge fails the
     * booking stays ACTIVE with these kept, so a retry bills exactly the same rental; on completion they move
     * into endTime, distanceKm and totalCost.
     */
    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    @Column(name = "pending_distance_km", precision = 10, scale = 2)
    private BigDecimal pendingDistanceKm;

    @Column(name = "pending_total_cost", precision = 10, scale = 2)
    private BigDecimal pendingTotalCost;

    /** SDS 2.2: address string of the pickup point. */
    @Column(name = "pickup_location", nullable = false, length = 300)
    private String pickupLocation;

    /** SDS 2.2: GPS address of the return point; NULL until the rental ends. */
    @Column(name = "drop_location", length = 300)
    private String dropLocation;

    @Column(length = 2000)
    private String notes;

    /** SDS 2.2: TRUE once the customer has signed the rental contract; required before activation. */
    @Column(name = "contract_signed", nullable = false)
    private boolean contractSigned;

    @Column(name = "cancelled_at")
    private LocalDateTime cancelledAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<BookingGear> gear = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void addGear(BookingGear line) {
        line.setBooking(this);
        gear.add(line);
    }
}

