package com.scooterrentalkandy.spring.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "gps_pings")
@Getter
@Setter
public class GpsPing {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scooter_id")
    private Scooter scooter;

    /** SDS 2.2 GPS Log: the active rental this position was logged during (NOT NULL). */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "booking_id", nullable = false)
    private Booking booking;

    /** SDS 2.2: WGS84 decimal degrees, DECIMAL(9,6). */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal latitude;

    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal longitude;

    /** SDS 2.2: km/h, DECIMAL(5,2) NOT NULL, >= 0. */
    @Column(name = "speed_kmh", nullable = false, precision = 5, scale = 2)
    private BigDecimal speedKmh;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;
}
