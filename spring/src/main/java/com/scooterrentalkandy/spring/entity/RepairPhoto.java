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
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

/**
 * A photo attached to a repair report (V19). The bytes are in the photo store under {@code storageKey}, a
 * server-generated name; the uploaded file name is never stored or used.
 */
@Entity
@Table(name = "repair_photos")
@Getter
@Setter
public class RepairPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "repair_photo_id", nullable = false, updatable = false)
    private UUID repairPhotoId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "repair_report_id", nullable = false)
    private RepairReport repairReport;

    /** 1 to 3, in upload order. */
    @Column(nullable = false)
    private short position;

    @Column(name = "storage_key", nullable = false, length = 64, updatable = false)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
