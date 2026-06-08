package com.scooterrentalkandy.spring.entity;

import com.scooterrentalkandy.spring.common.enums.BookingStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
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

    // ── Foreign Keys ───────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scooter_id", nullable = false)
    private Scooter scooter;

    // ── Attributes ─────────────────────────────────────────────────────────────

    @Column(name = "start_time", nullable = false)
    private Instant startTime;

    /** NULL while rental is still active. */
    @Column(name = "end_time")
    private Instant endTime;

    @Column(name = "pickup_location", nullable = false, length = 300)
    private String pickupLocation;

    /** NULL until rental ends. */
    @Column(name = "drop_location", length = 300)
    private String dropLocation;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private BookingStatus status;

    /**
     * Calculated final cost in LKR. NULL until booking completes.
     * Persisted deliberately as an immutable billing record (documented 3NF exception).
     */
    @Column(name = "total_cost", precision = 10, scale = 2)
    private BigDecimal totalCost;

    /** Must be TRUE before booking can be activated. */
    @Column(name = "contract_signed", nullable = false)
    @Builder.Default
    private Boolean contractSigned = false;

    // ── Relationships ──────────────────────────────────────────────────────────

    @ManyToOne(fetch = FetchType.LAZY, cascade = CascadeType.ALL)
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<GpsLog> gpsLogs;

    @OneToMany(mappedBy = "booking", cascade = CascadeType.ALL)
    private List<BookingGear> bookingGears;
}

