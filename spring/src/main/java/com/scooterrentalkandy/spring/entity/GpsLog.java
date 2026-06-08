package com.scooterrentalkandy.spring.entity;


import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "gps_logs",
        indexes = {
                @Index(name = "idx_gps_logs_booking_id",   columnList = "booking_id"),
                @Index(name = "idx_gps_logs_recorded_at",  columnList = "recorded_at")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GpsLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "log_id", nullable = false, updatable = false)
    private UUID logId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** GPS latitude in WGS84 decimal degrees. */
    @Column(name = "latitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    /** GPS longitude in WGS84 decimal degrees. */
    @Column(name = "longitude", nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    /** Scooter speed in km/h at the time of logging. */
    @Column(name = "speed", nullable = false, precision = 5, scale = 2)
    private BigDecimal speed;

    /** ISO 8601 UTC; logged every 30 seconds by the GPS device. */
    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;
}
