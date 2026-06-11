package com.scooterrentalkandy.spring.entity;

import com.scooterrentalkandy.spring.common.enums.UserRole;

import com.scooterrentalkandy.spring.common.enums.ScooterStatus;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Column(name = "model", nullable = false, length = 150)
    private String model;

    @Column(name = "registration_no", nullable = false, unique = true, length = 30)
    private String registrationNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ScooterStatus status;

    /** Rental charge per hour in LKR. */
    @Column(name = "hourly_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal hourlyRate;

    /** Distance-based charge per kilometer in LKR. */
    @Column(name = "per_km_rate", nullable = false, precision = 10, scale = 2)
    private BigDecimal perKmRate;

    /** AWS S3 URL for the scooter image (JPEG/PNG, max 5 MB). */
    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** Cumulative distance travelled in kilometers. */
    @Column(name = "total_mileage", precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal totalMileage = BigDecimal.ZERO;

    /** Date of most recent maintenance service (ISO 8601). */
    @Column(name = "last_service_date")
    private LocalDate lastServiceDate;

    // ── Relationships ──────────────────────────────────────────────────────────

    @OneToMany(mappedBy = "scooter", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<Booking> bookings;
}
