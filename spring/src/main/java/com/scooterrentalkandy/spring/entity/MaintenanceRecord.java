package com.scooterrentalkandy.spring.entity;


import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "maintenance_records")
@Getter
@Setter
public class MaintenanceRecord {

    public enum Type { SERVICE, REPAIR, INSPECTION }

    public enum Status { SCHEDULED, IN_PROGRESS, DONE, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "maintenance_record_id", nullable = false, updatable = false)
    private UUID maintenanceRecordId;


    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scooter_id")
    private Scooter scooter;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, length = 2000)
    private String description;

    @Column(precision = 12, scale = 2)
    private BigDecimal cost;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "completed_date")
    private LocalDate completedDate;

    @Column(name = "odometer_km")
    private Integer odometerKm;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
