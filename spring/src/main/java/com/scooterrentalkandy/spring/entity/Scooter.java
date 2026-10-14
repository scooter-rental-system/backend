package com.scooterrentalkandy.spring.entity;


import com.scooterrentalkandy.spring.entity.enums.ScooterStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scooters")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Scooter {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "scooter_id", nullable = false, updatable = false)
    private UUID scooterId;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String model;

    @Column(name = "plate_number", nullable = false, unique = true)
    private String plateNumber;

    @Column(name = "engine_cc")
    private Integer engineCc;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ScooterStatus status = ScooterStatus.AVAILABLE;

    /** SDS 2.2: rental charge per hour (LKR). */
    @Column(name = "hourly_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    /** SDS 2.2: distance charge per kilometre (LKR). */
    @Column(name = "per_km_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal perKmRate;

    /** SDS 2.2: cumulative kilometres travelled. */
    @Column(name = "total_mileage", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalMileage = BigDecimal.ZERO.setScale(2);

    private Double latitude;

    private Double longitude;

    @Column(name = "image_url")
    private String imageUrl;

    @Column(length = 2000)
    private String description;

    /** Last-known position, time and speed from any ping (idle pings are not stored as GPS logs). */
    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    /** Speed of the last ping; a violation fires when it goes from <= 60 to > 60 km/h (SDS 4.3). */
    @Column(name = "last_speed_kmh", precision = 5, scale = 2)
    private BigDecimal lastSpeedKmh;

    /** SDS 8.3: scooters leaving the fleet are soft-deleted and kept for history. */
    @Column(nullable = false)
    private boolean deleted;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
